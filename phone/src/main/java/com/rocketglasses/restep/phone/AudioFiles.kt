package com.rocketglasses.restep.phone

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File

/** 手順の音声ファイル（WAV、または StepMemo の Opus 入り OGG）を扱う。 */
object AudioFiles {
    fun mimeType(file: File) = if (file.extension == "ogg") "audio/ogg" else "audio/wav"

    /** 文字起こしに送るための WAV。OGG は端末で PCM に戻してから WAV にする（送り先の対応形式に左右されないため）。 */
    fun wavBytes(file: File): ByteArray = if (file.extension == "wav") file.readBytes() else decodeToWav(file)

    private fun decodeToWav(file: File): ByteArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("音声が見つかりません")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = SystemClock.uptimeMillis() + 60_000
            decode@ while (true) {
                check(SystemClock.uptimeMillis() < deadline) { "音声の変換が終わりません" }
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val n = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                while (true) {
                    val index = decoder.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        sampleRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (index >= 0) {
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(index)!!
                            val chunk = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(chunk)
                            pcm.write(chunk)
                        }
                        decoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@decode
                    }
                }
            }
            return wav(pcm.toByteArray(), sampleRate, channels)
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    /** 16bit PCM に 44 バイトの WAV ヘッダーを付ける。 */
    internal fun wav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val out = ByteArray(44 + pcm.size)
        fun int(offset: Int, value: Int) { for (i in 0..3) out[offset + i] = ((value shr (8 * i)) and 0xff).toByte() }
        fun short(offset: Int, value: Int) { out[offset] = (value and 0xff).toByte(); out[offset + 1] = ((value shr 8) and 0xff).toByte() }
        fun text(offset: Int, value: String) { value.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() } }
        text(0, "RIFF"); int(4, 36 + pcm.size); text(8, "WAVE")
        text(12, "fmt "); int(16, 16); short(20, 1); short(22, channels)
        int(24, sampleRate); int(28, sampleRate * channels * 2); short(32, channels * 2); short(34, 16)
        text(36, "data"); int(40, pcm.size)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }
}
