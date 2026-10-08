package com.rocketglasses.restep.phone

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID

/** 文字起こしの接続先設定（この端末の中だけに保存。API キーは外に出さない）。 */
class TranscriptionSettings(context: Context) {
    private val prefs = context.getSharedPreferences("transcription", Context.MODE_PRIVATE)
    /** 0 = OpenRouter（JSON）, 1 = OpenAI 互換（自宅サーバーなど。multipart） */
    var mode: Int
        get() = prefs.getInt("mode", MODE_OPENROUTER)
        set(v) { prefs.edit().putInt("mode", v).apply() }
    var baseUrl: String
        get() = prefs.getString("baseUrl", OPENROUTER_URL) ?: OPENROUTER_URL
        set(v) { prefs.edit().putString("baseUrl", v.trim()).apply() }
    var model: String
        get() = prefs.getString("model", OPENROUTER_MODEL) ?: OPENROUTER_MODEL
        set(v) { prefs.edit().putString("model", v.trim()).apply() }
    var apiKey: String
        get() = prefs.getString("apiKey", "") ?: ""
        set(v) { prefs.edit().putString("apiKey", v.trim()).apply() }

    companion object {
        const val MODE_OPENROUTER = 0
        const val MODE_OPENAI = 1
        const val OPENROUTER_URL = "https://openrouter.ai/api/v1"
        const val OPENROUTER_MODEL = "qwen/qwen3-asr-flash-2026-02-10"
        const val OPENAI_URL = "http://192.168.0.10:8000/v1"
        const val OPENAI_MODEL = "whisper-1"
        val OPENROUTER_BACKUP_MODEL = "openai/whisper-large-v3-turbo"
    }
}

/** 音声ファイルを文字に変える。OpenRouter 形式と、OpenAI 互換形式（/audio/transcriptions）の両方に対応。 */
object Transcriber {
    /** audio は WAV か、StepMemo の Opus 入り OGG（WAV に戻して送る）。 */
    fun transcribe(settings: TranscriptionSettings, audio: File): String {
        require(settings.baseUrl.isNotBlank()) { "接続先の URL が未設定です" }
        require(settings.model.isNotBlank()) { "モデル名が未設定です" }
        if (settings.mode == TranscriptionSettings.MODE_OPENROUTER)
            require(settings.apiKey.isNotBlank()) { "OpenRouter の API キーが未設定です" }
        val wav = AudioFiles.wavBytes(audio)
        val url = URL(settings.baseUrl.trimEnd('/') + "/audio/transcriptions")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            if (settings.apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer " + settings.apiKey)
            val body: ByteArray
            if (settings.mode == TranscriptionSettings.MODE_OPENROUTER) {
                connection.setRequestProperty("Content-Type", "application/json")
                val data = Base64.getEncoder().encodeToString(wav)
                body = JSONObject().put("model", settings.model)
                    .put("input_audio", JSONObject().put("data", data).put("format", "wav"))
                    .put("language", "ja").toString().toByteArray(Charsets.UTF_8)
            } else {
                val boundary = "----restep" + UUID.randomUUID().toString().replace("-", "")
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                body = multipart(boundary, wav, settings.model)
            }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) error("文字起こしに失敗しました（HTTP $code）: ${text.take(200)}")
            return JSONObject(text).optString("text").trim()
        } finally {
            connection.disconnect()
        }
    }

    private fun multipart(boundary: String, wav: ByteArray, model: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", model)
        field("language", "ja")
        field("response_format", "json")
        out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
            "Content-Type: audio/wav\r\n\r\n").toByteArray())
        out.write(wav)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }
}

/**
 * 文字起こし結果の後処理（画面や通信に依存しない）。
 *  1. 「やり直し」があれば、最後のやり直しより前を捨てる。
 *  2. 末尾の「送信」（操作の言葉）を取り除く。
 */
object TranscriptCleaner {
    private val redo = Regex("(やり直し|やりなおし)(?![たてをがはのにでも])")
    private val trailingSend = Regex("[\\s、。，,.!！?？]*(メモ送信|送信して|送信する|送信|そうしん)[\\s、。，,.!！?？]*$")
    private val edgePunctuation = Regex("^[\\s、。，,.!！?？]+|[\\s、。，,.!！?？]+$")

    /** 整えた文字を返す。内容が空（やり直し・送信だけ）のときは空文字。 */
    fun clean(raw: String): String {
        var text = raw
        redo.findAll(text).lastOrNull()?.let { text = text.substring(it.range.last + 1) }
        while (true) {
            val next = trailingSend.replace(text, "")
            if (next == text) break
            text = next
        }
        return text.replace(edgePunctuation, "")
    }
}
