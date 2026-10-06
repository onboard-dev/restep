package com.rocketglasses.restep.phone

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings() = (0 until length()).map { getString(it) }.toSet()

class Library(private val root: File) {
    private val disk = AtomicFile(File(root, "library.json"))
    var data: JSONObject = if (disk.baseFile.exists()) JSONObject(disk.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        else JSONObject().put("schemaVersion", 1).put("sessions", JSONArray())
        private set
    fun sessions() = data.getJSONArray("sessions").objects()
    fun deleted() = data.optJSONArray("deletedSessionIDs")?.strings() ?: emptySet()
    fun pending() = data.optJSONArray("pendingDeletionIDs")?.strings() ?: emptySet()
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
    fun delete(id: String) {
        commit(JSONObject(data.toString()).put("sessions", JSONArray(sessions().filter { it.getString("id") != id }))
            .put("deletedSessionIDs", JSONArray((deleted() + id).toList()))
            .put("pendingDeletionIDs", JSONArray((pending() + id).toList())))
        cleanup()
    }
    fun acknowledge(id: String) { commit(JSONObject(data.toString()).put("pendingDeletionIDs", JSONArray((pending() - id).toList()))) }
    fun photo(path: String): File {
        require(path.matches(Regex("photos/[a-f0-9-]{36}/[a-f0-9-]{36}\\.jpg"))) { "Invalid photo path" }
        return File(root, path)
    }
    fun audio(path: String): File {
        require(path.matches(Regex("audio/[a-f0-9-]{36}/[a-f0-9-]{36}\\.wav"))) { "Invalid audio path" }
        return File(root, path)
    }
    private fun write(target: File, bytes: ByteArray) {
        target.parentFile!!.mkdirs()
        val atomic = AtomicFile(target); val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch(e: Exception) { atomic.failWrite(out); throw e }
    }
    fun storePhoto(path: String, bytes: ByteArray) = write(photo(path), bytes)
    fun storeAudio(path: String, bytes: ByteArray) = write(audio(path), bytes)
    fun merge(remote: JSONObject) {
        require(remote.getInt("schemaVersion") == 1) { "Unsupported glasses library" }
        val all = sessions().associateBy { it.getString("id") }.toMutableMap()
        for (incoming in remote.getJSONArray("sessions").objects()) {
            val id = incoming.getString("id"); if(id in deleted()) continue
            val old = all[id]
            if (old != null) {
                incoming.put("name", old.getString("name"))
                val steps = old.getJSONArray("steps").objects().associateBy { it.getString("id") }
                for(step in incoming.getJSONArray("steps").objects()) steps[step.getString("id")]?.let {
                    step.put("note", it.getString("note")).put("completed", it.optBoolean("completed"))
                    if (it.has("transcribed")) step.put("transcribed", it.getBoolean("transcribed"))
                }
            }
            all[id] = incoming
        }
        commit(JSONObject(data.toString()).put("sessions", JSONArray(all.values.sortedByDescending { it.getLong("createdAt") })))
        cleanup()
    }
    private fun cleanup() {
        val steps = sessions().flatMap { it.getJSONArray("steps").objects() }
        val used = steps.map { photo(it.getString("photo")).canonicalPath }.toSet()
        File(root,"photos").walkTopDown().filter { it.isFile && it.extension == "jpg" && it.canonicalPath !in used }.forEach { it.delete() }
        val usedAudio = steps.mapNotNull { it.optString("audio").takeIf { path -> path.isNotEmpty() } }.map { audio(it).canonicalPath }.toSet()
        File(root,"audio").walkTopDown().filter { it.isFile && it.extension == "wav" && it.canonicalPath !in usedAudio }.forEach { it.delete() }
    }
}
