package com.rocketglasses.soberyobratno

/** 16kHz・モノラル・16bit の生の音声（PCM）を WAV ファイルのバイト列にする。 */
internal object Wav {
    const val SAMPLE_RATE = 16000

    /** ShortArray の先頭 count 個を、リトルエンディアンのバイト列にする。 */
    fun toLittleEndian(samples: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    /** PCM に 44 バイトの WAV ヘッダーを付ける。 */
    fun wrap(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val out = ByteArray(44 + pcm.size)
        fun int(offset: Int, value: Int) {
            for (i in 0..3) out[offset + i] = ((value shr (8 * i)) and 0xff).toByte()
        }
        fun short(offset: Int, value: Int) {
            out[offset] = (value and 0xff).toByte(); out[offset + 1] = ((value shr 8) and 0xff).toByte()
        }
        fun text(offset: Int, value: String) { value.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() } }
        text(0, "RIFF"); int(4, 36 + pcm.size); text(8, "WAVE")
        text(12, "fmt "); int(16, 16); short(20, 1) /* PCM */; short(22, 1) /* mono */
        int(24, sampleRate); int(28, sampleRate * 2); short(32, 2); short(34, 16)
        text(36, "data"); int(40, pcm.size)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }
}
