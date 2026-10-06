package com.rocketglasses.soberyobratno

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.concurrent.atomic.AtomicBoolean

class VoiceEngine(
    private val context: Context,
    private val onCommand: (String) -> Unit,
    private val onNote: (String, ByteArray?) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private var model: Model? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var cameraSuspended = false
    private val running = AtomicBoolean(false)
    @Volatile private var desiredRunning = false
    @Volatile private var loading = false
    @Volatile private var noteMode = false
    @Volatile private var muted = false
    @Volatile private var draft = ""
    @Volatile private var partial = ""
    @Volatile private var submitRequested = false
    // メモ中の音声（16kHz モノラル PCM）。文字起こしの精度検証と、あとでスマホで文字起こしするために保存する。
    private val audioLock = Any()
    private var noteAudio: java.io.ByteArrayOutputStream? = null
    // マイクの読み取り専用スレッド。認識（重い処理）と分けて、録音が途切れないようにする。
    @Volatile private var captureThread: Thread? = null
    private val captureRunning = AtomicBoolean(false)
    private val chunks = java.util.concurrent.LinkedBlockingQueue<ShortArray>(CHUNK_QUEUE_LIMIT)

    init {
        // 旧バージョンが展開したロシア語・英語モデルを削除してストレージを空ける。
        Thread {
            listOf("model-ru", "model-en").forEach { name ->
                context.getExternalFilesDir(null)?.resolve(name)?.takeIf { it.exists() }?.deleteRecursively()
            }
        }.apply { name = "OldModelCleanup"; start() }
    }

    @Synchronized fun start() {
        desiredRunning = true
        startCapture()   // モデルの読み込みを待たずに、先にマイクを開く
        if (cameraSuspended || running.get() || worker != null || loading) return
        val ready = model
        if (ready != null) { startWorker(ready); return }
        loading = true
        onStatus("音声認識を読み込み中")
        StorageService.unpack(context.applicationContext, "model-ja", "model-ja",
            { loaded ->
                synchronized(this) {
                    if (cameraSuspended) loaded.close() else model = loaded
                    loading = false
                    if (desiredRunning) start()
                }
            }, { error ->
                loading = false
                onStatus("音声: ${error.message ?: "エラー"}")
            })
    }

    private fun startWorker(ja: Model) {
        running.set(true)
        worker = Thread { loop(ja) }.apply { name = "MemoryVoice"; start() }
    }

    fun expectNote(initialText: String = "") {
        synchronized(audioLock) { noteAudio = java.io.ByteArrayOutputStream() }
        draft = initialText; partial = ""; submitRequested = false; noteMode = true
    }
    fun cancelNote() {
        synchronized(audioLock) { noteAudio = null }
        noteMode = false; draft = ""; partial = ""; submitRequested = false
    }

    private fun appendAudio(samples: ShortArray, count: Int) {
        synchronized(audioLock) {
            val out = noteAudio ?: return
            if (out.size() >= MAX_AUDIO_BYTES) return
            val bytes = Wav.toLittleEndian(samples, count)
            out.write(bytes, 0, bytes.size)
        }
    }

    /** ここまでに録音したメモの音声を WAV にして返し、録音をリセットする。短すぎる場合は null。 */
    fun takeAudio(): ByteArray? = synchronized(audioLock) {
        val out = noteAudio
        noteAudio = null
        if (out == null || out.size() < MIN_AUDIO_BYTES) null else Wav.wrap(out.toByteArray())
    }
    fun currentDraft(): String = draft + partial
    fun submitNote(): Boolean {
        if (!noteMode || !running.get()) return false
        submitRequested = true
        return true
    }
    fun mute(value: Boolean) { muted = value }

    /** Release native model/decoder memory before the camera allocates its capture pipeline. */
    fun suspendForCamera(onReady: (Boolean) -> Unit) {
        cameraSuspended = true
        running.set(false)
        val previous = worker
        previous?.interrupt()
        Thread {
            previous?.join(6000)
            val deadline = SystemClock.uptimeMillis() + 10000
            while (loading && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
            val released = synchronized(this) {
                if (previous?.isAlive == true || loading) false
                else {
                    model?.close()
                    model = null
                    true
                }
            }
            Log.i("MemoryVoice", "Speech memory released for camera: $released")
            onReady(released)
        }.apply { name = "CameraMemoryRelease"; start() }
    }

    @Synchronized fun resumeAfterCamera() {
        cameraSuspended = false
        if (desiredRunning) start()
    }

    fun close() {
        desiredRunning = false
        suspendForCamera { }
        Thread { stopCapture() }.apply { name = "MicStop"; start() }
    }

    @Synchronized private fun startCapture() {
        if (captureThread?.isAlive == true) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("音声: マイクの権限がありません"); return
        }
        captureRunning.set(true)
        captureThread = Thread { captureLoop() }.apply { name = "MemoryMic"; start() }
    }

    private fun stopCapture() {
        captureRunning.set(false)
        captureThread?.interrupt()
        captureThread?.join(2000)
        captureThread = null
        chunks.clear()
    }

    /** マイクを読み続ける。認識の速さに関係なく、メモ中の音声はここで直接録音する。 */
    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var record: AudioRecord? = null
        try {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            require(min > 0) { "マイクが使えません" }
            // 以前は約0.1秒分しかなく、認識が遅れるとその間の音声が捨てられていた。4秒分に広げる。
            record = AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, Wav.SAMPLE_RATE * 2 * 4))
            check(record.state == AudioRecord.STATE_INITIALIZED) { "マイクを開けません" }
            record.startRecording()
            val buffer = ShortArray(800)
            while (captureRunning.get()) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                if (muted) continue
                if (noteMode) appendAudio(buffer, n)
                if (running.get()) {
                    // 認識が追いつかないときは古い音声から捨てる（録音そのものには影響しない）。
                    if (!chunks.offer(buffer.copyOf(n))) { chunks.poll(); chunks.offer(buffer.copyOf(n)) }
                } else if (chunks.isNotEmpty()) chunks.clear()
            }
        } catch (e: Exception) {
            onStatus("音声: ${e.message ?: "エラー"}")
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            record?.release()
            captureRunning.set(false)
        }
    }

    private fun loop(ja: Model) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        // 待機中も入力中も、日本語モデル1つの自由認識だけを使う（メモリ節約）。
        var recognizer: Recognizer? = null
        try {
            chunks.clear()
            onStatus(if (noteMode) "メモ入力中。「送信」で保存" else "「撮影」と言ってください")
            var wasNote: Boolean? = null
            var lastPartialAt = 0L
            while (running.get()) {
                val data = chunks.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                val n = data.size
                val buffer = data
                val nowNote = noteMode
                if (nowNote != wasNote) {
                    // モードが変わったら認識器を作り直し、前のモードの音声を持ち越さない。
                    partial = ""
                    recognizer?.close()
                    recognizer = Recognizer(ja, 16000f)
                    wasNote = nowNote
                }
                val rec = recognizer ?: continue
                if (nowNote) {
                    if (submitRequested) {
                        val finalText = VoiceCommands.normalize(JSONObject(rec.finalResult).optString("text"))
                        submitRequested = false
                        finishNote(finalText.ifBlank { partial })
                        continue
                    }
                    if (!rec.acceptWaveForm(buffer, n)) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastPartialAt >= 200) {
                            partial = VoiceCommands.normalize(JSONObject(rec.partialResult).optString("partial"))
                            lastPartialAt = now
                            if (VoiceCommands.endsWithRedo(partial)) {
                                // 「やり直し」: ここまでの文字と音声を捨てて、次の発話から録り直す。
                                rec.reset()
                                redoNote()
                            }
                        }
                        continue
                    }
                    partial = ""
                    val text = VoiceCommands.normalize(JSONObject(rec.result).optString("text"))
                    if (text.isEmpty()) continue
                    Log.i("MemoryVoice", "Note heard: $text")
                    var heard = text
                    if (VoiceCommands.containsRedo(heard)) {
                        // 途中で「やり直し」が検出されなかった場合: その言葉より前を捨てる。
                        redoNote()
                        heard = VoiceCommands.afterLastRedo(heard)
                    }
                    val submission = VoiceCommands.submission(heard)
                    if (submission.send) finishNote(heard)
                    else {
                        draft = draft + heard
                        onStatus("メモ入力中。「送信」で保存")
                    }
                } else {
                    if (!rec.acceptWaveForm(buffer, n)) continue
                    val text = VoiceCommands.normalize(JSONObject(rec.result).optString("text"))
                    if (text.isEmpty()) continue
                    Log.i("MemoryVoice", "Heard while waiting: $text")
                    if (VoiceCommands.isPhotoCommand(text)) {
                        rec.reset()
                        onCommand(COMMAND_PHOTO)
                    }
                }
            }
        } catch (e: InterruptedException) {
            // 停止要求
        } catch (e: Exception) {
            onStatus("音声: ${e.message ?: "エラー"}")
        } finally {
            recognizer?.close()
            running.set(false)
            worker = null
        }
    }

    private fun redoNote() {
        draft = ""; partial = ""
        synchronized(audioLock) { if (noteAudio != null) noteAudio = java.io.ByteArrayOutputStream() }
        onStatus("やり直し。もう一度どうぞ")
    }

    private fun finishNote(text: String, commandConfirmed: Boolean = false) {
        draft = VoiceCommands.submission(draft + text, commandConfirmed).note
        partial = ""
        noteMode = false
        onNote(draft, takeAudio())
    }

    companion object {
        const val COMMAND_PHOTO = "photo"
        private const val MAX_AUDIO_BYTES = Wav.SAMPLE_RATE * 2 * 120 // 最長 120 秒
        private const val MIN_AUDIO_BYTES = Wav.SAMPLE_RATE * 2 / 5   // 0.2 秒未満は保存しない
        private const val CHUNK_QUEUE_LIMIT = 200                       // 50ms × 200 = 約10秒
    }

    fun stop() {
        desiredRunning = false
        running.set(false)
        worker?.interrupt()
        worker?.join(2000)
        stopCapture()
        // A live native decoder must finish before another worker can use its models.
    }
}
