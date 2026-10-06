package com.rocketglasses.soberyobratno

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class TransferServer(private val store: SessionStore, private val code: String,
                     private val directToken: () -> String? = { null },
                     private val port: Int = 8765,
                     private val onSynced: (List<String>) -> Unit = {},
                     private val deleteSession: (String) -> Boolean = { store.deleteSession(it); true }) {
    private val inFlight = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var lastRequestAt = 0L
    /** スマホからの要求を処理中、または直後。 */
    val transferring: Boolean get() = inFlight.get() > 0 || System.currentTimeMillis() - lastRequestAt < 1500
    /** 最近スマホから要求があった（つながっている）。 */
    val recentlyActive: Boolean get() = lastRequestAt != 0L && System.currentTimeMillis() - lastRequestAt < 20000
    val everTransferred: Boolean get() = lastRequestAt != 0L
    private var server: ServerSocket? = null
    private var workers = Executors.newCachedThreadPool()

    fun start() {
        if (server != null) return
        if (workers.isShutdown) workers = Executors.newCachedThreadPool()
        val socket = ServerSocket(port)
        server = socket
        workers.execute {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    workers.execute { handle(client) }
                }
                catch (_: Exception) { break }
            }
        }
    }

    fun stop() {
        server?.close()
        server = null
        workers.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10000
            val input = client.getInputStream().bufferedReader()
            val request = input.readLine() ?: return
            var supplied = ""
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("X-Pair-Code:", ignoreCase = true)) supplied = line.substringAfter(':').trim()
            }
            val out = client.getOutputStream()
            val authorized = !(supplied != code && (directToken() == null || supplied != directToken()))
            if (authorized) inFlight.incrementAndGet()
            try {
            if (!authorized) {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else if (request.startsWith("DELETE /session/")) {
                val id = request.substringAfter("DELETE /session/").substringBefore(' ')
                val result = if (!id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
                    "400 Bad Request"
                } else try {
                    if (deleteSession(id)) "204 No Content" else "409 Conflict"
                } catch (_: Exception) { "500 Internal Server Error" }
                out.write("HTTP/1.1 $result\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else if (request.startsWith("GET /manifest ")) {
                val body = store.manifest()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(body)
            } else if (request.startsWith("GET /photo/")) {
                val relative = request.substringAfter("GET /photo/").substringBefore(' ')
                val body = store.photo(relative)
                if (body == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                }
            } else if (request.startsWith("GET /audio/")) {
                val relative = request.substringAfter("GET /audio/").substringBefore(' ')
                val body = store.audio(relative)
                if (body == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                }
            } else if (request.startsWith("GET /synced?ids=")) {
                // スマホが保存に成功した手順の ID。グラスの「未同期」の数え方に使う。
                val ids = request.substringAfter("GET /synced?ids=").substringBefore(' ').split(',')
                    .filter { it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) }
                onSynced(ids)
                out.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else if (request.startsWith("GET /export ")) {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename=memory.zip\r\nConnection: close\r\n\r\n".toByteArray())
                store.exportTo(out)
            } else {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
            out.flush()
            } finally {
                if (authorized) { inFlight.decrementAndGet(); lastRequestAt = System.currentTimeMillis() }
            }
        }
    }
}
