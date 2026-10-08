package com.rocketglasses.soberyobratno

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File

/**
 * メモの音声（16kHz・モノラル・16bit の PCM）を Opus（OGG 入り）に圧縮する。
 * 端末の c2.android.opus.encoder を使う（2026-10-08 の実機検証で、品質は AAC と同程度で少し小さい）。
 */
internal object OpusEncoder {
    const val FORMAT = "opus"
    private const val BITRATE = 24_000
    private const val TIMEOUT_US = 10_000L
    private const val LIMIT_MS = 30_000L // 最長 120 秒の音声でも数秒で終わる。止まったときの保険。

    fun encode(pcm: ByteArray, workDir: File): ByteArray {
        require(pcm.isNotEmpty()) { "音声が空です" }
        val out = File.createTempFile("note", ".ogg", workDir)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        var muxer: MediaMuxer? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, Wav.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            var track = -1
            var offset = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.uptimeMillis() + LIMIT_MS
            encode@ while (true) {
                check(SystemClock.uptimeMillis() < deadline) { "音声の圧縮が終わりません" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        val n = minOf(buffer.remaining(), pcm.size - offset) and 1.inv() // 16bit 単位で区切る
                        buffer.put(pcm, offset, n)
                        val timeUs = offset / 2 * 1_000_000L / Wav.SAMPLE_RATE
                        offset += n
                        inputDone = offset >= pcm.size - 1
                        codec.queueInputBuffer(index, 0, n, timeUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                // 出ている分はすぐ取り出す。待つのは入力を渡し終えたあとだけ（細かい区切りごとに待つと遅くなる）。
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (inputDone) TIMEOUT_US else 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    } else if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)!!
                        // 設定データ（Opus のヘッダー）は addTrack の形式に含まれるので、音声として書かない。
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(track >= 0) { "音声の形式が決まる前にデータが来ました" }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@encode
                    }
                }
            }
            muxer.stop()
            return out.readBytes()
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            muxer?.release()
            out.delete()
        }
    }
}
