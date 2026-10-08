package com.rocketglasses.stepmemo.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO

class StepMemoJpegTest {
    private val meta = StepMemoMeta("0b4c1f2e-3a5d-4c6e-8f70-123456789abc", 3, "2026-10-08T21:04:00+09:00")

    /** 画像を作って JPEG のバイト列にする（noise=true だと 0xFF が多く、エントロピー部分の検査になる）。 */
    private fun makeJpeg(width: Int = 64, height: Int = 48, noise: Boolean = false): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val rnd = Random(42)
        for (y in 0 until height) for (x in 0 until width) {
            img.setRGB(x, y, if (noise) rnd.nextInt(0x1000000) else (x * 4 shl 16) or (y * 5 shl 8) or 0x80)
        }
        val out = ByteArrayOutputStream()
        check(ImageIO.write(img, "jpg", out))
        return out.toByteArray()
    }

    /** JFIF の直後に、EXIF 風の APP1 を入れる（カメラの JPEG の並びに近づける）。 */
    private fun withExifLike(jpeg: ByteArray): ByteArray {
        assertEquals(0xE0, jpeg[3].toInt() and 0xFF)
        val app0End = 2 + 2 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF))
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(40) { it.toByte() }
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, (payload.size + 2).toByte()) + payload
        return jpeg.copyOfRange(0, app0End) + seg + jpeg.copyOfRange(app0End, jpeg.size)
    }

    /** SOS より前のマーカーの並び。 */
    private fun markers(b: ByteArray): List<Int> {
        val list = ArrayList<Int>()
        var pos = 2
        while (true) {
            val m = b[pos + 1].toInt() and 0xFF
            list.add(m)
            if (m == 0xDA) return list
            pos += 2 + (((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF))
        }
    }

    private fun decodes(b: ByteArray, width: Int, height: Int) {
        val img = ImageIO.read(ByteArrayInputStream(b))
        assertNotNull(img)
        assertEquals(width, img.width)
        assertEquals(height, img.height)
    }

    private fun audioBytes(size: Int) = ByteArray(size).also { Random(7).nextBytes(it) }

    @Test fun descriptionIsAsciiAndParses() {
        val text = StepMemoJpeg.description(meta)
        assertEquals("#StepMemo sid=0b4c1f2e-3a5d-4c6e-8f70-123456789abc step=3", text)
        assertTrue(text.all { it.code in 0x20..0x7E })
        assertEquals(meta.sessionId to 3, StepMemoJpeg.parseDescription(text))
        assertNull(StepMemoJpeg.parseDescription("#Other sid=abc step=1"))
        assertNull(StepMemoJpeg.parseDescription("#StepMemo sid=abc step=0"))
        assertNull(StepMemoJpeg.parseDescription(null))
    }

    @Test fun japaneseNoteSurvivesRoundTrip() {
        val note = "玄関のパキラの鉢を外す。\"みかん\" & <箱> 'A' 😀\n2行目"
        val m = meta.copy(note = note)
        val file = StepMemoJpeg.embed(makeJpeg(), m)
        assertEquals(m, StepMemoJpeg.readMeta(file))
    }

    @Test fun emptyNoteAndNoAudio() {
        val file = StepMemoJpeg.embed(makeJpeg(), meta)
        val read = StepMemoJpeg.readMeta(file)
        assertEquals(meta, read)
        assertEquals("", read!!.note)
        assertNull(read.audioFormat)
        assertNull(StepMemoJpeg.readAudio(file))
    }

    @Test fun imageStillDecodes() {
        decodes(StepMemoJpeg.embed(makeJpeg(80, 60), meta.copy(note = "テスト")), 80, 60)
        decodes(StepMemoJpeg.embed(makeJpeg(80, 60), meta.copy(audioFormat = "aac"), audioBytes(5000)), 80, 60)
    }

    @Test fun xmpGoesAfterExifAndBeforeTables() {
        val base = withExifLike(makeJpeg())
        val before = markers(base)
        assertEquals(listOf(0xE0, 0xE1), before.take(2))
        val after = markers(StepMemoJpeg.embed(base, meta))
        assertEquals(listOf(0xE0, 0xE1, 0xE1), after.take(3))
        assertEquals(before.drop(2), after.drop(3))
    }

    @Test fun originalBytesArePreservedAroundXmp() {
        // 画像の中身（0xFF00 などを含むノイズ画像）は1バイトも変わらない
        val base = withExifLike(makeJpeg(120, 90, noise = true))
        val file = StepMemoJpeg.embed(base, meta.copy(note = "x"))
        val xmpLen = file.size - base.size
        val xmpStart = 2 + 18 + (4 + 6 + 40) // SOI + JFIF(APP0 全体) + EXIF 風 APP1 全体
        // JFIF の長さは 16 なので APP0 は 18 バイト
        assertArrayEquals(base.copyOfRange(0, xmpStart), file.copyOfRange(0, xmpStart))
        assertArrayEquals(base.copyOfRange(xmpStart, base.size), file.copyOfRange(xmpStart + xmpLen, file.size))
        decodes(file, 120, 90)
    }

    @Test fun embedTwiceReplacesInsteadOfDuplicating() {
        val first = StepMemoJpeg.embed(makeJpeg(), meta.copy(note = "最初", audioFormat = "aac"), audioBytes(3000))
        val second = StepMemoJpeg.embed(first, meta.copy(step = 4, note = "二回目"))
        assertEquals(1, markers(second).count { it == 0xE1 })
        assertEquals(meta.copy(step = 4, note = "二回目"), StepMemoJpeg.readMeta(second))
        assertNull(StepMemoJpeg.readAudio(second)) // 以前の音声は捨てられる
        decodes(second, 64, 48)
    }

    @Test fun audioRoundTrip() {
        val audio = audioBytes(120_000)
        val file = StepMemoJpeg.embed(makeJpeg(), meta.copy(note = "メモ", audioFormat = "aac"), audio)
        assertEquals(StepMemoAudio("aac", audio), StepMemoJpeg.readAudio(file))
        assertEquals(StepMemoAudio("aac", audio), StepMemoJpeg.readAudio(ByteArrayRead(file)))
        val read = StepMemoJpeg.readMeta(file)!!
        assertEquals("aac", read.audioFormat)
        assertEquals("メモ", read.note)
    }

    @Test fun formatNamesShorterThanFourCharsWork() {
        val audio = audioBytes(100)
        val file = StepMemoJpeg.embed(makeJpeg(), meta.copy(audioFormat = "wav"), audio)
        assertEquals("wav", StepMemoJpeg.readAudio(file)!!.format)
    }

    @Test fun corruptedAudioIsRejectedButMetaIsStillReadable() {
        val audio = audioBytes(10_000)
        val file = StepMemoJpeg.embed(makeJpeg(), meta.copy(audioFormat = "aac"), audio)
        val broken = file.copyOf()
        broken[broken.size - 16 - 5_000] = (broken[broken.size - 16 - 5_000].toInt() xor 0x55).toByte()
        assertNull(StepMemoJpeg.readAudio(broken))
        assertNotNull(StepMemoJpeg.readMeta(broken))
        assertNull(StepMemoJpeg.readAudio(file.copyOf(file.size - 3))) // 途中で切れた
    }

    @Test fun metaIsReadFromHeadOnly() {
        val file = StepMemoJpeg.embed(makeJpeg(), meta.copy(note = "先頭だけで読める", audioFormat = "aac"), audioBytes(300_000))
        assertTrue(file.size > 300_000)
        assertEquals("先頭だけで読める", StepMemoJpeg.readMeta(file.copyOf(2048))!!.note)
        assertNull(StepMemoJpeg.readMeta(file.copyOf(40))) // 短すぎれば null（例外にしない）
    }

    @Test fun otherFilesAreNotStepMemo() {
        assertNull(StepMemoJpeg.readMeta(makeJpeg()))
        assertNull(StepMemoJpeg.readAudio(makeJpeg()))
        assertNull(StepMemoJpeg.readMeta(ByteArray(1000) { it.toByte() }))
        assertNull(StepMemoJpeg.readMeta(ByteArray(0)))
        assertNull(StepMemoJpeg.readAudio(ByteArray(10)))
        // 他のアプリの XMP だけがある JPEG
        val other = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".toByteArray(Charsets.ISO_8859_1)
        val base = makeJpeg()
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((other.size + 2) shr 8).toByte(), (other.size + 2).toByte()) + other
        val withOther = base.copyOfRange(0, 20) + seg + base.copyOfRange(20, base.size)
        assertNull(StepMemoJpeg.readMeta(withOther))
    }

    @Test fun otherXmpIsReplacedAndTrailingGarbageDropped() {
        val base = makeJpeg()
        val other = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".toByteArray(Charsets.ISO_8859_1)
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((other.size + 2) shr 8).toByte(), (other.size + 2).toByte()) + other
        val dirty = base.copyOfRange(0, 20) + seg + base.copyOfRange(20, base.size) + ByteArray(50) { 9 }
        val file = StepMemoJpeg.embed(dirty, meta)
        assertEquals(1, markers(file).count { it == 0xE1 })
        assertEquals(meta, StepMemoJpeg.readMeta(file))
        assertTrue(file.size < dirty.size + 600 - 50) // 末尾の 50 バイトは残らない（他の XMP も置き換わる）
        decodes(file, 64, 48)
    }

    @Test fun invalidInputIsRejected() {
        fun fails(block: () -> Unit) {
            try { block() } catch (e: IllegalArgumentException) { return }
            fail("IllegalArgumentException が出るはず")
        }
        fails { StepMemoJpeg.embed(ByteArray(100), meta) }
        fails { StepMemoJpeg.embed(makeJpeg().copyOf(100), meta) }
        fails { StepMemoJpeg.embed(makeJpeg(), meta.copy(audioFormat = "aac")) } // 形式だけあって音声が無い
        fails { StepMemoJpeg.embed(makeJpeg(), meta, audioBytes(10)) }             // 音声だけあって形式が無い
        fails { StepMemoMeta("has space", 1, "x") }
        fails { StepMemoMeta("ok", 0, "x") }
        fails { StepMemoMeta("ok", 1, "x", audioFormat = "AAC!") }
    }

    @Test fun controlCharactersAreDroppedFromNote() {
        val m = meta.copy(note = "a\u0000b\u0001c\u000bd\ud800e") // XML に使えない文字
        val read = StepMemoJpeg.readMeta(StepMemoJpeg.embed(makeJpeg(), m))
        assertEquals("abcde", read!!.note)
    }

    @Test fun escapeHandlesSymbols() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;", StepMemoJpeg.escape("&<>\"'"))
        assertFalse(StepMemoJpeg.escape("a\u0000").contains('\u0000'))
    }
}
