package com.rocketglasses.soberyobratno

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @Test fun littleEndianConversion() {
        val bytes = Wav.toLittleEndian(shortArrayOf(1, -2, 0x1234), 3)
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0xFE.toByte(), 0xFF.toByte(), 0x34, 0x12), bytes)
        assertEquals(2, Wav.toLittleEndian(shortArrayOf(5, 6, 7), 1).size)
    }

    @Test fun headerDescribesMono16kPcm() {
        val pcm = ByteArray(32000) { (it % 7).toByte() } // 1 秒
        val wav = Wav.wrap(pcm)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals("data", String(wav, 36, 4))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + pcm.size, b.getInt(4))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt())      // PCM
        assertEquals(1, b.getShort(22).toInt())      // モノラル
        assertEquals(16000, b.getInt(24))
        assertEquals(32000, b.getInt(28))            // バイト/秒
        assertEquals(2, b.getShort(32).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(pcm.size, b.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }
}
