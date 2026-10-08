package com.rocketglasses.restep.phone

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings() = (0 until length()).map { getString(it) }.toSet()

class Library(private val root: File) {
    companion object { const val SOURCE_ALBUM = "album" }
    private val disk = AtomicFile(File(root, "library.json"))
    var data: JSONObject = if (disk.baseFile.exists()) JSONObject(disk.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        else JSONObject().put("schemaVersion", 1).put("sessions", JSONArray())
        private set
    fun sessions() = data.getJSONArray("sessions").objects()
    fun deleted() = data.optJSONArray("deletedSessionIDs")?.strings() ?: emptySet()
    /** アルバムで調べ終えたファイル（「名前|大きさ|更新日時」）。次の取り込みで読み直さない。 */
    fun scanned() = data.optJSONArray("scannedAlbumKeys")?.strings() ?: emptySet()
    fun markScanned(keys: Collection<String>) {
        if (keys.isEmpty()) return
        commit(JSONObject(data.toString()).put("scannedAlbumKeys", JSONArray((scanned() + keys).toList())))
    }
    fun hasStep(sessionId: String, stepId: String) = sessions().firstOrNull { it.getString("id") == sessionId }
        ?.getJSONArray("steps")?.objects()?.any { it.getString("id") == stepId } == true
    private fun commit(next: JSONObject) {
        root.mkdirs()
        val out = disk.startWrite()
        try { out.write(next.toString().toByteArray()); disk.finishWrite(out); data = next }
        catch (e: Exception) { disk.failWrite(out); throw e }
    }
    fun edit(id: String, change: (JSONObject) -> Unit) {
        val next = JSONObject(data.toString())
        next.getJSONArray("sessions").objects().firstOrNull { it.getString("id") == id }?.let(change)
        commit(next)
    }
    /** 削除したプロジェクトの ID は覚えておき、アルバムから取り込み直さない。 */
    fun delete(id: String) {
        commit(JSONObject(data.toString()).put("sessions", JSONArray(sessions().filter { it.getString("id") != id }))
            .put("deletedSessionIDs", JSONArray((deleted() + id).toList()))
            .apply { remove("pendingDeletionIDs") }) // 旧同期でグラスに伝える予定だった削除（もう使わない）
        cleanup()
    }
    fun photo(path: String): File {
        require(path.matches(Regex("photos/[a-f0-9-]{36}/[a-f0-9-]{36}\\.jpg"))) { "Invalid photo path" }
        return File(root, path)
    }
    fun audio(path: String): File {
        require(path.matches(Regex("audio/[a-f0-9-]{36}/[a-f0-9-]{36}\\.(wav|ogg)"))) { "Invalid audio path" }
        return File(root, path)
    }
    private fun write(target: File, bytes: ByteArray) {
        target.parentFile!!.mkdirs()
        val atomic = AtomicFile(target); val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch(e: Exception) { atomic.failWrite(out); throw e }
    }
    fun storePhoto(path: String, bytes: ByteArray) = write(photo(path), bytes)
    fun storeAudio(path: String, bytes: ByteArray) = write(audio(path), bytes)
    /** アルバムから取り込んだ1手順を加える（写真・音声は先に保存しておく）。手順は番号順に並べる。 */
    fun addAlbumStep(sessionId: String, takenAt: Long, step: JSONObject) {
        val next = JSONObject(data.toString())
        val all = next.getJSONArray("sessions").objects().toMutableList()
        val session = all.firstOrNull { it.getString("id") == sessionId } ?: JSONObject().apply {
            put("id", sessionId)
            put("name", "記録 " + SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(takenAt)))
            put("createdAt", takenAt)
            put("source", SOURCE_ALBUM)
            put("steps", JSONArray())
        }.also { all.add(it) }
        if (takenAt < session.getLong("createdAt")) session.put("createdAt", takenAt)
        val steps = (session.getJSONArray("steps").objects() + step)
            .sortedWith(compareBy<JSONObject>({ it.getInt("number") }, { it.optLong("createdAt") }))
        session.put("steps", JSONArray(steps))
        commit(next.put("sessions", JSONArray(all.sortedByDescending { it.getLong("createdAt") })))
    }
    private fun cleanup() {
        val steps = sessions().flatMap { it.getJSONArray("steps").objects() }
        val used = steps.map { photo(it.getString("photo")).canonicalPath }.toSet()
        File(root,"photos").walkTopDown().filter { it.isFile && it.extension == "jpg" && it.canonicalPath !in used }.forEach { it.delete() }
        val usedAudio = steps.mapNotNull { it.optString("audio").takeIf { path -> path.isNotEmpty() } }.map { audio(it).canonicalPath }.toSet()
        File(root,"audio").walkTopDown().filter { it.isFile && it.extension in setOf("wav", "ogg") && it.canonicalPath !in usedAudio }.forEach { it.delete() }
    }
}
