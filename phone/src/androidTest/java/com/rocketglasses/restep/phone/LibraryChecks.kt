package com.rocketglasses.restep.phone

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

class LibraryChecks {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun testAlbumStepsDeletionAndRestart() {
        val root = File(context.cacheDir, "test-${UUID.randomUUID()}")
        val sid = "106e608b-f2dd-45b2-b5f6-a53b5ddad9d3"
        val session = AlbumKeys.sessionId(sid)
        fun step(number: Int, at: Long): JSONObject {
            val id = AlbumKeys.stepId(sid, number, "hash$number")
            val photo = "photos/$session/$id.jpg"
            val audio = "audio/$session/$id.ogg"
            return JSONObject().put("id", id).put("number", number).put("photo", photo).put("audio", audio)
                .put("note", "手順$number").put("createdAt", at)
        }
        try {
            val lib = Library(root)
            val second = step(2, 2_000); val first = step(1, 1_000)
            for (s in listOf(second, first)) {
                lib.storePhoto(s.getString("photo"), byteArrayOf(1, 2, 3))
                lib.storeAudio(s.getString("audio"), byteArrayOf(4, 5, 6))
            }
            // 取り込む順番が逆でも、手順番号の順に並び、プロジェクトの開始は早いほうの時刻になる。
            lib.addAlbumStep(session, 2_000, second)
            lib.addAlbumStep(session, 1_000, first)
            val restarted = Library(root)
            val saved = restarted.sessions().single()
            assertEquals(Library.SOURCE_ALBUM, saved.getString("source"))
            assertEquals(1_000L, saved.getLong("createdAt"))
            assertEquals(listOf(1, 2), saved.getJSONArray("steps").objects().map { it.getInt("number") })
            assertTrue(restarted.hasStep(session, first.getString("id")))

            restarted.markScanned(listOf("a.jpg|1|1"))
            assertTrue("a.jpg|1|1" in Library(root).scanned())

            restarted.delete(session)
            val again = Library(root)
            assertTrue(session in again.deleted())
            assertTrue(again.sessions().isEmpty())
            assertFalse(again.photo(first.getString("photo")).exists())
            assertFalse(again.audio(first.getString("audio")).exists())
            try { again.photo("../../secret.jpg"); fail("Traversal accepted") } catch (_: IllegalArgumentException) { }
            try { again.audio("audio/$session/x.mp3"); fail("Unknown audio accepted") } catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }
}
