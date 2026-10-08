package com.rocketglasses.restep.phone

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.rocketglasses.stepmemo.format.StepMemoJpeg
import org.json.JSONObject
import java.io.InputStream

/**
 * Hi Rokid の同期でスマホに届いた StepMemo の写真（Download/Hi Rokid）を探して、手順として取り込む。
 * 元の写真は読むだけで書き換えない。写真はアプリの中にコピーし、末尾の音声は別ファイルに取り出す。
 */
class AlbumImport(private val context: Context, private val library: Library) {
    /** 取り込み候補の1ファイル。key は「名前|大きさ|更新日時(秒)」で、一度調べたファイルを読み直さないために使う。 */
    class Candidate(val key: String, val name: String, val open: () -> InputStream)

    class Result {
        var checked = 0; var imported = 0; var withAudio = 0; var already = 0; var failed = 0
    }

    /** MediaStore から Download/Hi Rokid の画像を探す（写真の読み取り権限が必要）。 */
    fun fromMediaStore(): List<Candidate> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        val found = mutableListOf<Candidate>()
        context.contentResolver.query(collection, projection, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$FOLDER%"), "${MediaStore.MediaColumns.DISPLAY_NAME} ASC")?.use { c ->
            while (c.moveToNext()) {
                val item = ContentUris.withAppendedId(collection, c.getLong(0))
                val name = c.getString(1) ?: continue
                found += Candidate("$name|${c.getLong(2)}|${c.getLong(3)}", name) {
                    context.contentResolver.openInputStream(item) ?: error("開けません: $name")
                }
            }
        }
        return found
    }

    /** 利用者が選んだフォルダ（権限を断ったときの代わり）から JPEG を探す。 */
    fun fromFolder(tree: Uri): List<Candidate> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        val found = mutableListOf<Candidate>()
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(4) != "image/jpeg") continue
                val item = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                val name = c.getString(1) ?: continue
                found += Candidate("$name|${c.getLong(2)}|${c.getLong(3) / 1000}", name) {
                    context.contentResolver.openInputStream(item) ?: error("開けません: $name")
                }
            }
        }
        return found.sortedBy { it.name }
    }

    /** 候補を順に調べて取り込む。StepMemo でない写真は先頭だけ読んで飛ばす。 */
    fun run(candidates: List<Candidate>, progress: (Int, Int) -> Unit): Result {
        val result = Result().apply { checked = candidates.size }
        val scanned = library.scanned()
        val done = mutableListOf<String>()
        try {
            for ((index, candidate) in candidates.withIndex()) {
                if (candidate.key in scanned) continue
                progress(index + 1, candidates.size)
                try {
                    if (importOne(candidate, result)) done += candidate.key
                } catch (e: Exception) {
                    // 調べ終えた印を付けないので、次の取り込みでやり直す。
                    Log.w("AlbumImport", "Import failed: ${candidate.name}", e)
                    result.failed++
                }
            }
        } finally {
            library.markScanned(done)
        }
        return result
    }

    /** 1ファイルを調べる。調べ終えた（取り込んだ・StepMemo でない・取り込み済み）なら true。 */
    private fun importOne(candidate: Candidate, result: Result): Boolean {
        val meta = candidate.open().use { StepMemoJpeg.readMeta(it.buffered()) } ?: return true
        val bytes = candidate.open().use { it.readBytes() }
        val sessionId = AlbumKeys.sessionId(meta.sessionId)
        val stepId = AlbumKeys.stepId(meta.sessionId, meta.step, AlbumKeys.sha256(bytes))
        if (sessionId in library.deleted() || library.hasStep(sessionId, stepId)) { result.already++; return true }
        val photoPath = "photos/$sessionId/$stepId.jpg"
        library.storePhoto(photoPath, bytes)
        val audio = StepMemoJpeg.readAudio(bytes)
        val audioPath = audio?.let { a -> AlbumKeys.audioExtension(a.format)?.let { "audio/$sessionId/$stepId.$it" } }
        if (audio != null && audioPath != null) library.storeAudio(audioPath, audio.bytes)
        val takenAt = AlbumKeys.takenAtMillis(meta.takenAt) ?: System.currentTimeMillis()
        library.addAlbumStep(sessionId, takenAt, JSONObject().apply {
            put("id", stepId)
            put("number", meta.step)
            put("photo", photoPath)
            put("note", meta.note.trim()) // グラスの仮の文字。文字起こしで置き換える。
            audioPath?.let { put("audio", it) }
            put("createdAt", takenAt)
            put("source", candidate.name)
        })
        result.imported++
        if (audioPath != null) result.withAudio++
        return true
    }

    companion object {
        const val FOLDER = "Download/Hi Rokid/"
    }
}
