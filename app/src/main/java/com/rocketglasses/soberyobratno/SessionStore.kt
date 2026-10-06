package com.rocketglasses.soberyobratno

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SessionStore(context: Context) {
    private val root = File(context.filesDir, "memory").apply { mkdirs() }
    private val index = File(root, "index.json")
    private val photos = File(root, "photos").apply { mkdirs() }
    private val photoPath = Regex("photos/[0-9a-f-]{36}/[0-9a-f-]{36}\\.jpg")
    private val audioPath = Regex("audio/[0-9a-f-]{36}/[0-9a-f-]{36}\\.wav")
    private var data = if (index.exists()) JSONObject(index.readText()) else JSONObject().apply {
        put("schemaVersion", 1)
        put("sessions", JSONArray())
    }

    init {
        // Older versions used the last entry as the current session. Repeated swipes
        // could leave empty entries after the last session containing photographs.
        if (!data.has("activeSessionId")) {
            val all = data.getJSONArray("sessions")
            val withPhotos = (0 until all.length()).map { all.getJSONObject(it) }
                .filter { it.getJSONArray("steps").length() > 0 }
            val active = withPhotos.maxByOrNull { session ->
                val steps = session.getJSONArray("steps")
                steps.getJSONObject(steps.length() - 1).optLong("createdAt", 0)
            } ?: (0 until all.length()).map { all.getJSONObject(it) }
                .maxByOrNull { it.optLong("createdAt", 0) }
            for (i in 0 until all.length()) {
                val session = all.getJSONObject(i)
                if (session.optString("id") != active?.optString("id") && !session.has("endedAt")) {
                    val steps = session.getJSONArray("steps")
                    val lastTime = if (steps.length() > 0)
                        steps.getJSONObject(steps.length() - 1).optLong("createdAt", 0)
                    else session.optLong("createdAt", 0)
                    session.put("endedAt", lastTime)
                }
            }
            data.put("activeSessionId", active?.getString("id") ?: JSONObject.NULL)
            save()
        }
        try { cleanupDeletedPhotos() }
        catch (e: Exception) { android.util.Log.w("MemoryStore", "Pending photo cleanup will retry", e) }
    }

    @Synchronized fun sessions(): JSONArray = JSONArray(data.getJSONArray("sessions").toString())

    @Synchronized fun manifest(): ByteArray = data.toString(2).toByteArray(Charsets.UTF_8)

    @Synchronized fun deleteSession(id: String) {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        val previous = data
        val next = JSONObject(data.toString())
        val all = next.getJSONArray("sessions")
        val remaining = JSONArray()
        val cleanup = next.optJSONArray("pendingPhotoDeletes") ?: JSONArray()
        for (i in 0 until all.length()) {
            val session = all.getJSONObject(i)
            if (session.getString("id") != id) remaining.put(session)
            else {
                val steps = session.getJSONArray("steps")
                for (j in 0 until steps.length()) {
                    val step = steps.getJSONObject(j)
                    cleanup.put(step.getString("photo"))
                    step.optString("audio").takeIf { it.isNotEmpty() }?.let { cleanup.put(it) }
                }
            }
        }
        next.put("sessions", remaining)
        next.put("pendingPhotoDeletes", cleanup)
        if (next.optString("activeSessionId") == id) next.put("activeSessionId", JSONObject.NULL)
        data = next
        try { save() } catch (e: Exception) { data = previous; throw e }
        // The durable cleanup list makes interruption/retry safe after removing the index entry.
        cleanupDeletedPhotos()
    }

    private fun cleanupDeletedPhotos() {
        val cleanup = data.optJSONArray("pendingPhotoDeletes") ?: return
        val retained = mutableSetOf<String>()
        val all = data.getJSONArray("sessions")
        for (i in 0 until all.length()) {
            val steps = all.getJSONObject(i).getJSONArray("steps")
            for (j in 0 until steps.length()) {
                val step = steps.getJSONObject(j)
                retained.add(step.getString("photo"))
                step.optString("audio").takeIf { it.isNotEmpty() }?.let { retained.add(it) }
            }
        }
        val failed = JSONArray()
        for (i in 0 until cleanup.length()) {
            val relative = cleanup.getString(i)
            if (relative in retained || !(photoPath.matches(relative) || audioPath.matches(relative))) continue
            val file = File(root, relative)
            if (file.exists() && !file.delete()) failed.put(relative)
            else file.parentFile?.delete() // Removes the folder only if it is empty.
        }
        data.put("pendingPhotoDeletes", failed)
        save()
        if (failed.length() > 0) throw java.io.IOException("Photo deletion incomplete; retry sync")
    }

    @Synchronized fun photo(relative: String): ByteArray? {
        if (!relative.matches(Regex("photos/[0-9a-f-]{36}/[0-9a-f-]{36}\\.jpg"))) return null
        val file = File(root, relative)
        return if (file.isFile) file.readBytes() else null
    }

    @Synchronized fun audio(relative: String): ByteArray? {
        if (!audioPath.matches(relative)) return null
        val file = File(root, relative)
        return if (file.isFile) file.readBytes() else null
    }

    @Synchronized fun current(): JSONObject? {
        if (data.isNull("activeSessionId")) return null
        val activeId = data.optString("activeSessionId")
        val all = data.getJSONArray("sessions")
        return (0 until all.length()).map { all.getJSONObject(it) }
            .firstOrNull { it.optString("id") == activeId }
    }

    @Synchronized fun newSession(): JSONObject {
        current()?.let { return it }
        val session = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("name", "分解 " + SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date()))
            put("createdAt", System.currentTimeMillis())
            put("steps", JSONArray())
        }
        data.getJSONArray("sessions").put(session)
        data.put("activeSessionId", session.getString("id"))
        save()
        return session
    }

    @Synchronized fun finishSession(): Boolean {
        val session = current() ?: return false
        session.put("endedAt", System.currentTimeMillis())
        data.put("activeSessionId", JSONObject.NULL)
        save()
        return true
    }

    @Synchronized fun addStep(jpeg: ByteArray, note: String, audio: ByteArray? = null): JSONObject {
        val session = current() ?: newSession()
        return appendStep(session, jpeg, note, audio)
    }

    @Synchronized fun addCameraSample(jpeg: ByteArray) {
        val now = System.currentTimeMillis()
        val sample = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("name", "Camera sample — 1600 × 1200")
            put("createdAt", now)
            put("endedAt", now)
            put("steps", JSONArray())
        }
        data.getJSONArray("sessions").put(sample)
        appendStep(sample, jpeg, "Camera sample: 1600 × 1200, automatic exposure. Use Show original to compare.")
    }

    private fun appendStep(session: JSONObject, jpeg: ByteArray, note: String, audio: ByteArray? = null): JSONObject {
        val id = UUID.randomUUID().toString()
        val relative = "photos/${session.getString("id")}/$id.jpg"
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output -> output.write(jpeg); output.fd.sync() }
        var audioRelative: String? = null
        if (audio != null) {
            // 音声は付属データ。保存に失敗しても写真とメモは残す。
            val candidate = "audio/${session.getString("id")}/$id.wav"
            try {
                val audioFile = File(root, candidate)
                audioFile.parentFile?.mkdirs()
                FileOutputStream(audioFile).use { output -> output.write(audio); output.fd.sync() }
                audioRelative = candidate
            } catch (e: Exception) { android.util.Log.w("MemoryStore", "Audio not saved", e) }
        }
        val step = JSONObject().apply {
            put("id", id)
            put("number", session.getJSONArray("steps").length() + 1)
            put("photo", relative)
            put("note", note.trim())
            audioRelative?.let { put("audio", it) }
            put("createdAt", System.currentTimeMillis())
        }
        session.getJSONArray("steps").put(step)
        save()
        return step
    }

    @Synchronized fun exportTo(output: java.io.OutputStream) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(data.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            val all = data.getJSONArray("sessions")
            for (i in 0 until all.length()) {
                val steps = all.getJSONObject(i).getJSONArray("steps")
                for (j in 0 until steps.length()) {
                    val step = steps.getJSONObject(j)
                    val files = listOfNotNull(step.getString("photo"), step.optString("audio").takeIf { it.isNotEmpty() })
                    for (relative in files) {
                        val file = File(root, relative)
                        if (file.isFile) {
                            zip.putNextEntry(ZipEntry(relative))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
        }
    }

    private fun save() {
        val temp = File(root, "index.tmp")
        FileOutputStream(temp).use { output ->
            output.write(data.toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temp.renameTo(index)) {
            temp.copyTo(index, overwrite = true)
            temp.delete()
        }
    }
}
