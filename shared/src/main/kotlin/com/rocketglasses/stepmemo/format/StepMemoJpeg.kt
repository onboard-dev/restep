package com.rocketglasses.stepmemo.format

import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.util.zip.CRC32
import javax.xml.parsers.DocumentBuilderFactory

/*
 * StepMemo の写真ファイルの形式（グラス側とスマホ側で共通）
 *
 *   JPEG ┬ SOI
 *        ├ APP0 / APP1(EXIF) ...   ← 触らない（向きなどはそのまま）
 *        ├ APP1(XMP)               ← StepMemo の情報（作業ID・手順番号・撮影日時・説明文）
 *        ├ DQT ... SOS ... EOI     ← 画像そのもの
 *        └ 末尾の付加データ（任意）  ← 音声（STEPMEMO ヘッダー + 音声 + CRC + 長さ + STEPMEMO）
 *
 * Android 専用のクラスは使わない（JVM だけで試験できる）。
 * EXIF の ImageDescription（半角英数字のみ）には [StepMemoJpeg.description] の文字列を、
 * グラス側で Android の ExifInterface を使って別に書く。日本語の本文は XMP に入れる。
 */

/** 1枚の写真に付ける情報。 */
data class StepMemoMeta(
    /** 作業（セッション）の識別番号。半角英数字とハイフンのみ。 */
    val sessionId: String,
    /** 手順の番号（1 から）。 */
    val step: Int,
    /** 撮影日時（ISO-8601、例: 2026-10-08T21:04:00+09:00）。 */
    val takenAt: String,
    /** 説明文（日本語可）。空でもよい。 */
    val note: String = "",
    /** 末尾に音声を付けるときの形式（"aac" など。半角英数字1〜4文字）。付けないなら null。 */
    val audioFormat: String? = null,
) {
    init {
        require(SESSION_ID.matches(sessionId)) { "sessionId は半角英数字とハイフンの1〜64文字: $sessionId" }
        require(step in 1..9999) { "step は 1〜9999: $step" }
        require(audioFormat == null || AUDIO_FORMAT.matches(audioFormat)) { "audioFormat が不正: $audioFormat" }
    }

    private companion object {
        val SESSION_ID = Regex("[0-9A-Za-z-]{1,64}")
        val AUDIO_FORMAT = Regex("[0-9a-z]{1,4}")
    }
}

/** 末尾に付けた音声。 */
class StepMemoAudio(val format: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is StepMemoAudio && format == other.format && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * format.hashCode() + bytes.contentHashCode()
}

/** 先頭からでなくても読めるように、位置を指定して読む入口。 */
interface RandomRead {
    val length: Long

    /** position から size バイトを読む。範囲外なら IllegalArgumentException。 */
    fun readAt(position: Long, size: Int): ByteArray
}

class ByteArrayRead(private val bytes: ByteArray) : RandomRead {
    override val length: Long get() = bytes.size.toLong()

    override fun readAt(position: Long, size: Int): ByteArray {
        require(position >= 0 && size >= 0 && position + size <= bytes.size) { "範囲外の読み出し" }
        return bytes.copyOfRange(position.toInt(), position.toInt() + size)
    }
}

object StepMemoJpeg {
    /** 識別タグ。一度決めたら変えないこと（過去の写真を見つけられなくなる）。 */
    const val TAG = "#StepMemo"

    /** XMP の名前空間（名前としてだけ使い、アクセスはしない）。 */
    const val NAMESPACE = "http://stepmemo.invalid/ns/1.0/"

    /** 音声の最大サイズ。 */
    const val MAX_AUDIO_BYTES = 16 * 1024 * 1024

    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val FORMAT_VERSION = "1"
    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private val MAGIC = "STEPMEMO".toByteArray(Charsets.US_ASCII)
    private const val TRAILER_VERSION = 1
    private const val HEADER_SIZE = 13 // MAGIC(8) + version(1) + format(4)
    private const val FOOTER_SIZE = 16 // crc32(4) + length(4) + MAGIC(8)
    private val DESCRIPTION = Regex("^" + Regex.escape(TAG) + " sid=([0-9A-Za-z-]{1,64}) step=([0-9]{1,4})")

    // ---------------------------------------------------------------- EXIF 用の文字列

    /** EXIF の ImageDescription 用（半角英数字のみ）。Google フォト等のキャプションにも出る。 */
    fun description(meta: StepMemoMeta): String = "$TAG sid=${meta.sessionId} step=${meta.step}"

    /** [description] で作った文字列から（作業ID, 手順番号）を取り出す。タグが違えば null。 */
    fun parseDescription(text: String?): Pair<String, Int>? {
        val m = DESCRIPTION.find(text ?: return null) ?: return null
        val step = m.groupValues[2].toInt()
        return if (step in 1..9999) m.groupValues[1] to step else null
    }

    // ---------------------------------------------------------------- 書き込み

    /**
     * JPEG に StepMemo の情報（XMP）を入れ、audio があれば末尾に付ける。
     *
     * - 既存の XMP は置き換える（JPEG には XMP を1つしか持てないため）。
     * - 画像の終わり（EOI）より後ろのデータは捨てる（以前の StepMemo の音声を含む）。
     * - 位置情報などの EXIF には触らない。入れたくないものは、撮影側で入れないこと。
     */
    fun embed(jpeg: ByteArray, meta: StepMemoMeta, audio: ByteArray? = null): ByteArray {
        require((audio == null) == (meta.audioFormat == null)) {
            "audio と meta.audioFormat は両方ある（または両方ない）必要があります"
        }
        require(audio == null || audio.size <= MAX_AUDIO_BYTES) { "音声が大きすぎます: ${audio?.size}" }
        val layout = locate(jpeg)
        val xmp = xmpSegment(meta)
        val out = ByteArrayOutputStream(jpeg.size + xmp.size + (audio?.size ?: 0) + 64)
        out.write(jpeg, 0, 2) // SOI
        var leading = true
        var xmpDone = false
        for (s in layout.segments) {
            if (s.marker == 0xE1 && isXmp(jpeg, s)) continue // 既存の XMP は捨てる
            if (leading && s.marker != 0xE0 && s.marker != 0xE1) {
                out.write(xmp); xmpDone = true; leading = false
            }
            out.write(jpeg, s.start, s.end - s.start)
        }
        if (!xmpDone) out.write(xmp)
        out.write(jpeg, layout.scanStart, layout.eoiEnd - layout.scanStart)
        if (audio != null) writeTrailer(out, meta.audioFormat!!, audio)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- 読み出し

    /** 先頭部分（数十KB あれば足りる）から StepMemo の情報を読む。StepMemo の写真でなければ null。 */
    fun readMeta(head: ByteArray): StepMemoMeta? = readMeta(ByteArrayInputStream(head))

    /** ファイルを先頭から順に読みながら、StepMemo の情報を探す（画像の本体までは読まない）。 */
    fun readMeta(input: InputStream): StepMemoMeta? {
        try {
            if (input.read() != 0xFF || input.read() != 0xD8) return null
            while (true) {
                if (input.read() != 0xFF) return null
                var marker = input.read()
                while (marker == 0xFF) marker = input.read()
                if (marker < 0 || marker == 0xDA || marker == 0xD9) return null
                if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD7) continue
                val hi = input.read()
                val lo = input.read()
                if (lo < 0) return null
                val payload = hi * 256 + lo - 2
                if (payload < 0) return null
                if (marker == 0xE1 && payload >= XMP_HEADER.size) {
                    val data = readFully(input, payload) ?: return null
                    if (startsWith(data, 0, XMP_HEADER)) parseXmp(data, XMP_HEADER.size)?.let { return it }
                } else if (!skipFully(input, payload)) return null
            }
        } catch (e: IOException) {
            return null
        }
    }

    /** 末尾の音声を読む。無い・壊れている（CRC が合わない）ときは null。 */
    fun readAudio(source: RandomRead): StepMemoAudio? {
        try {
            val total = source.length
            if (total < HEADER_SIZE + FOOTER_SIZE + 4) return null
            val footer = source.readAt(total - FOOTER_SIZE, FOOTER_SIZE)
            if (!startsWith(footer, 8, MAGIC)) return null
            val crc = u32(footer, 0)
            val len = u32(footer, 4)
            if (len > MAX_AUDIO_BYTES) return null
            val start = total - FOOTER_SIZE - len - HEADER_SIZE
            if (start < 4) return null
            val header = source.readAt(start, HEADER_SIZE)
            if (!startsWith(header, 0, MAGIC) || header[8].toInt() != TRAILER_VERSION) return null
            val format = String(header, 9, 4, Charsets.US_ASCII).trim()
            val body = source.readAt(start + HEADER_SIZE, len.toInt())
            val actual = CRC32().apply { update(body) }.value
            if (actual != crc) return null
            return StepMemoAudio(format, body)
        } catch (e: IllegalArgumentException) {
            return null
        }
    }

    fun readAudio(jpeg: ByteArray): StepMemoAudio? = readAudio(ByteArrayRead(jpeg))

    // ---------------------------------------------------------------- XMP

    private fun xmpSegment(meta: StepMemoMeta): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        sb.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
        sb.append(" <rdf:RDF xmlns:rdf=\"").append(RDF_NS).append("\">\n")
        sb.append("  <rdf:Description rdf:about=\"\" xmlns:dc=\"").append(DC_NS)
            .append("\" xmlns:sm=\"").append(NAMESPACE).append("\"")
        sb.append(" sm:version=\"").append(FORMAT_VERSION).append("\"")
        sb.append(" sm:session=\"").append(meta.sessionId).append("\"")
        sb.append(" sm:step=\"").append(meta.step).append("\"")
        sb.append(" sm:takenAt=\"").append(escape(meta.takenAt)).append("\"")
        if (meta.audioFormat != null) sb.append(" sm:audio=\"").append(meta.audioFormat).append("\"")
        sb.append(">\n")
        if (meta.note.isNotEmpty()) {
            sb.append("   <dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">")
                .append(escape(meta.note)).append("</rdf:li></rdf:Alt></dc:description>\n")
        }
        sb.append("  </rdf:Description>\n </rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>")
        val packet = sb.toString().toByteArray(Charsets.UTF_8)
        val length = 2 + XMP_HEADER.size + packet.size
        require(length <= 0xFFFF) { "説明文が長すぎます" }
        val out = ByteArrayOutputStream(length + 2)
        out.write(0xFF); out.write(0xE1)
        out.write(length shr 8); out.write(length and 0xFF)
        out.write(XMP_HEADER)
        out.write(packet)
        return out.toByteArray()
    }

    private fun parseXmp(data: ByteArray, offset: Int): StepMemoMeta? {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            try { factory.isXIncludeAware = false } catch (_: Exception) {}
            factory.isExpandEntityReferences = false
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver(EntityResolver { _, _ -> InputSource(StringReader("")) })
            val doc = builder.parse(ByteArrayInputStream(data, offset, data.size - offset))
            val descriptions = doc.getElementsByTagNameNS(RDF_NS, "Description")
            for (i in 0 until descriptions.length) {
                val el = descriptions.item(i) as? org.w3c.dom.Element ?: continue
                val session = el.getAttributeNS(NAMESPACE, "session")
                if (session.isEmpty() || el.getAttributeNS(NAMESPACE, "version") != FORMAT_VERSION) continue
                val step = el.getAttributeNS(NAMESPACE, "step").toIntOrNull() ?: continue
                val note = el.getElementsByTagNameNS(DC_NS, "description").let { list ->
                    if (list.length == 0) "" else {
                        val li = (list.item(0) as org.w3c.dom.Element).getElementsByTagNameNS(RDF_NS, "li")
                        if (li.length == 0) "" else li.item(0).textContent ?: ""
                    }
                }
                val audio = el.getAttributeNS(NAMESPACE, "audio").ifEmpty { null }
                return StepMemoMeta(session, step, el.getAttributeNS(NAMESPACE, "takenAt"), note, audio)
            }
            return null
        } catch (e: Exception) {
            return null // 壊れた XMP・形式違い・値の範囲外は「StepMemo の写真ではない」として扱う
        }
    }

    /** XML に入れられない文字を除き、記号をエスケープする。 */
    internal fun escape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val valid = cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF ||
                cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
            if (!valid) continue
            when (cp) {
                '&'.code -> sb.append("&amp;")
                '<'.code -> sb.append("&lt;")
                '>'.code -> sb.append("&gt;")
                '"'.code -> sb.append("&quot;")
                '\''.code -> sb.append("&apos;")
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 末尾の音声

    private fun writeTrailer(out: ByteArrayOutputStream, format: String, audio: ByteArray) {
        val crc = CRC32().apply { update(audio) }.value
        out.write(MAGIC)
        out.write(TRAILER_VERSION)
        out.write(format.padEnd(4, ' ').toByteArray(Charsets.US_ASCII))
        out.write(audio)
        writeU32(out, crc)
        writeU32(out, audio.size.toLong())
        out.write(MAGIC)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        for (shift in intArrayOf(24, 16, 8, 0)) out.write(((v shr shift) and 0xFF).toInt())
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    // ---------------------------------------------------------------- JPEG の構造

    private class Seg(val marker: Int, val start: Int, val end: Int)

    private class Layout(val segments: List<Seg>, val scanStart: Int, val eoiEnd: Int)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    /** SOS より前のセグメントと、画像の終わり（EOI の直後）の位置を調べる。 */
    private fun locate(b: ByteArray): Layout {
        require(b.size >= 4 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8) { "JPEG ではありません" }
        val segs = ArrayList<Seg>()
        var pos = 2
        var scanStart = -1
        while (scanStart < 0) {
            require(pos + 1 < b.size) { "JPEG が途中で切れています" }
            require(u8(b, pos) == 0xFF) { "JPEG のマーカーが不正です" }
            val m = u8(b, pos + 1)
            when {
                m == 0xFF -> pos++
                m == 0xDA -> scanStart = pos
                m == 0xD9 || m == 0xD8 -> throw IllegalArgumentException("画像データがありません")
                m == 0x00 || m == 0x01 || m in 0xD0..0xD7 -> pos += 2
                else -> {
                    require(pos + 3 < b.size) { "JPEG が途中で切れています" }
                    val len = u16(b, pos + 2)
                    val end = pos + 2 + len
                    require(len >= 2 && end <= b.size) { "JPEG のセグメントが不正です" }
                    segs.add(Seg(m, pos, end))
                    pos = end
                }
            }
        }
        require(scanStart + 3 < b.size) { "JPEG が途中で切れています" }
        var p = scanStart + 2 + u16(b, scanStart + 2)
        while (p + 1 < b.size) {
            if (u8(b, p) != 0xFF) { p++; continue }
            val n = u8(b, p + 1)
            when {
                n == 0x00 || n in 0xD0..0xD7 -> p += 2
                n == 0xFF -> p += 1
                n == 0xD9 -> return Layout(segs, scanStart, p + 2)
                else -> { // プログレッシブ JPEG の途中のマーカー
                    require(p + 3 < b.size) { "JPEG が途中で切れています" }
                    p += 2 + u16(b, p + 2)
                }
            }
        }
        throw IllegalArgumentException("JPEG の終わり（EOI）が見つかりません")
    }

    private fun isXmp(b: ByteArray, s: Seg): Boolean = startsWith(b, s.start + 4, XMP_HEADER, s.end)

    private fun startsWith(b: ByteArray, offset: Int, prefix: ByteArray, limit: Int = b.size): Boolean {
        if (offset < 0 || offset + prefix.size > limit) return false
        for (i in prefix.indices) if (b[offset + i] != prefix[i]) return false
        return true
    }

    private fun readFully(input: InputStream, size: Int): ByteArray? {
        val buf = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buf, read, size - read)
            if (n < 0) return null
            read += n
        }
        return buf
    }

    private fun skipFully(input: InputStream, size: Int): Boolean {
        var left = size.toLong()
        while (left > 0) {
            val n = input.skip(left)
            if (n > 0) left -= n
            else {
                if (input.read() < 0) return false
                left -= 1
            }
        }
        return true
    }
}
