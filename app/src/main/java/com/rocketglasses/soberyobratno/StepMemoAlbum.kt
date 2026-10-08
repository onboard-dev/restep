package com.rocketglasses.soberyobratno

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.rocketglasses.stepmemo.format.StepMemoJpeg
import com.rocketglasses.stepmemo.format.StepMemoMeta
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * StepMemo の写真を端末のアルバム（DCIM/Camera）に保存する。Hi Rokid の標準アルバム同期でスマホへ運ばれ、
 * スマホには元とバイト単位で同じファイルが届く（2026-10-08 実機確認）。
 * 位置情報（GPS）は入れない。
 */
internal class StepMemoAlbum(private val context: Context) {

    /** 写真に情報と音声（WAV。null なら音声なし）を入れて保存し、ファイル名を返す。 */
    fun save(jpeg: ByteArray, sessionId: String, step: Int, note: String, wav: ByteArray?, takenAt: Long): String {
        val started = SystemClock.uptimeMillis()
        val audio = wav?.let { compress(it) }
        val encoded = SystemClock.uptimeMillis()
        val meta = StepMemoMeta(
            sessionId = sessionId, step = step,
            takenAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(takenAt)),
            note = note, audioFormat = audio?.first,
        )
        val bytes = StepMemoJpeg.embed(withDescription(jpeg, StepMemoJpeg.description(meta)), meta, audio?.second)
        val name = "StepMemo-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(takenAt)) +
            "-%02d.jpg".format(Locale.US, step)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("アルバムに書けません")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        Log.i("StepMemoAlbum", "Saved $name ${bytes.size} bytes audio=${audio?.first} ${audio?.second?.size} " +
            "encode=${encoded - started}ms total=${SystemClock.uptimeMillis() - started}ms")
        return name
    }

    /**
     * アルバムに保存できなかったときの控え。アプリのフォルダ（Android/data/…/files/unsaved）に
     * 写真と音声をそのまま残す（adb で取り出せる。アルバムには出ない）。
     */
    fun keepUnsaved(jpeg: ByteArray, sessionId: String, step: Int, wav: ByteArray?, takenAt: Long): File {
        val dir = File(context.getExternalFilesDir(null), "unsaved").apply { mkdirs() }
        val base = "StepMemo-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(takenAt)) +
            "-%02d-%s".format(Locale.US, step, sessionId.take(8))
        wav?.let { File(dir, "$base.wav").writeBytes(it) }
        return File(dir, "$base.jpg").apply { writeBytes(jpeg) }
    }

    /** Opus に圧縮する。端末で圧縮できないときは WAV のまま入れる（音声を失わないため）。 */
    private fun compress(wav: ByteArray): Pair<String, ByteArray> = try {
        OpusEncoder.FORMAT to OpusEncoder.encode(wav.copyOfRange(WAV_HEADER, wav.size), context.cacheDir)
    } catch (e: Exception) {
        Log.w("StepMemoAlbum", "Opus encoding failed; embedding WAV", e)
        "wav" to wav
    }

    /** EXIF の ImageDescription（半角英数字のみ）を書き、念のため位置情報を消す。 */
    private fun withDescription(jpeg: ByteArray, description: String): ByteArray {
        val temp = File.createTempFile("stepmemo", ".jpg", context.cacheDir)
        try {
            temp.writeBytes(jpeg)
            ExifInterface(temp.path).apply {
                setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
                GPS_TAGS.forEach { setAttribute(it, null) }
                saveAttributes()
            }
            return temp.readBytes()
        } finally {
            temp.delete()
        }
    }

    private companion object {
        const val WAV_HEADER = 44 // Wav.wrap が付けるヘッダーの長さ
        val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
        )
    }
}
