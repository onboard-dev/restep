package com.rocketglasses.restep.phone

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.OutputStream

/** 写真を EXIF の向きどおりに回転して読み込む（グラスの写真は向き情報つきで保存される）。 */
object Photos {
    fun load(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val degrees = when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }
}

/** プロジェクトを PDF にする。1手順 = 1ページ（A4 縦）: 見出し・写真・説明。 */
object PdfExporter {
    class Entry(val heading: String, val photo: File?, val note: String)

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40

    fun write(out: OutputStream, title: String, entries: List<Entry>) {
        val doc = PdfDocument()
        try {
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(23, 35, 39); textSize = 11f }
            val headingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 125, 115); textSize = 20f; isFakeBoldText = true }
            val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 15f }
            val contentW = PAGE_W - MARGIN * 2
            if (entries.isEmpty()) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
                page.canvas.drawText(title, MARGIN.toFloat(), MARGIN + 20f, headingPaint)
                doc.finishPage(page)
            }
            entries.forEachIndexed { index, entry ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create())
                val canvas = page.canvas
                var y = MARGIN.toFloat()
                canvas.drawText(title, MARGIN.toFloat(), y + 10f, titlePaint)
                canvas.drawText("${index + 1} / ${entries.size}", (PAGE_W - MARGIN - 40).toFloat(), y + 10f, titlePaint)
                y += 34f
                canvas.drawText(entry.heading, MARGIN.toFloat(), y + 16f, headingPaint)
                y += 36f
                val bitmap = entry.photo?.takeIf { it.exists() }?.let { Photos.load(it, 1400) }
                if (bitmap != null) {
                    val maxH = 470f
                    val scale = minOf(contentW.toFloat() / bitmap.width, maxH / bitmap.height)
                    val w = bitmap.width * scale
                    val h = bitmap.height * scale
                    val left = MARGIN + (contentW - w) / 2
                    canvas.drawBitmap(bitmap, null, RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    y += h + 18f
                    bitmap.recycle()
                }
                val text = entry.note.ifBlank { "（説明なし）" }
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, notePaint, contentW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build()
                canvas.save()
                canvas.translate(MARGIN.toFloat(), y)
                canvas.clipRect(0f, 0f, contentW.toFloat(), (PAGE_H - MARGIN - y).coerceAtLeast(0f))
                layout.draw(canvas)
                canvas.restore()
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
