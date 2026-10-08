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

/**
 * 作業（セッション）と手順の記録。手順番号と、画面に出す「前回のメモ」に使う。
 * 写真と音声は StepMemoAlbum がアルバム（DCIM/Camera）に保存するので、ここには持たない。
 */
class SessionStore(context: Context) {
    private val root = File(context.filesDir, "memory").apply { mkdirs() }
    private val index = File(root, "index.json")
    private var data = if (index.exists()) JSONObject(index.readText()) else JSONObject().apply {
        put("schemaVersion", 1)
        put("sessions", JSONArray())
        put("activeSessionId", JSONObject.NULL)
    }

    init {
        // 旧同期のために持っていた写真・音声の内部コピーを削除する（アルバムに保存済み）。
        listOf("photos", "audio").forEach { File(root, it).takeIf { dir -> dir.exists() }?.deleteRecursively() }
    }

    @Synchronized fun sessions(): JSONArray = JSONArray(data.getJSONArray("sessions").toString())

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

    /** 手順を1つ記録して返す（番号は作業の中で 1 から）。 */
    @Synchronized fun addStep(note: String): JSONObject {
        val session = current() ?: newSession()
        val step = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("number", session.getJSONArray("steps").length() + 1)
            put("note", note.trim())
            put("createdAt", System.currentTimeMillis())
        }
        session.getJSONArray("steps").put(step)
        save()
        return step
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
