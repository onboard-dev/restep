package com.rocketglasses.restep.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumKeysTest {
    private val sid = "106e608b-f2dd-45b2-b5f6-a53b5ddad9d3"
    private val idPattern = Regex("[a-f0-9-]{36}") // Library の写真・音声のパスに使える形

    @Test fun sameInputGivesSameIds() {
        assertEquals(AlbumKeys.sessionId(sid), AlbumKeys.sessionId(sid))
        assertEquals(AlbumKeys.stepId(sid, 1, "ab"), AlbumKeys.stepId(sid, 1, "ab"))
    }

    @Test fun idsFitLibraryPaths() {
        assertTrue(idPattern.matches(AlbumKeys.sessionId(sid)))
        assertTrue(idPattern.matches(AlbumKeys.sessionId("probe-20261008-214234")))
        assertTrue(idPattern.matches(AlbumKeys.stepId(sid, 3, AlbumKeys.sha256(byteArrayOf(1, 2, 3)))))
    }

    @Test fun sessionIdDiffersFromGlassesId() {
        // 旧同期で取り込んだ同じ作業と混ざらないように、グラスの ID そのままにはしない。
        assertNotEquals(sid, AlbumKeys.sessionId(sid))
    }

    @Test fun stepIdDependsOnStepAndContent() {
        val a = AlbumKeys.stepId(sid, 1, "aa")
        assertNotEquals(a, AlbumKeys.stepId(sid, 2, "aa"))
        assertNotEquals(a, AlbumKeys.stepId(sid, 1, "bb"))
        assertNotEquals(a, AlbumKeys.stepId("other", 1, "aa"))
    }

    @Test fun sha256IsHex() {
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", AlbumKeys.sha256(byteArrayOf(1, 2, 3)))
    }

    @Test fun parsesTakenAt() {
        assertEquals(1791465120000L, AlbumKeys.takenAtMillis("2026-10-08T22:12:00+09:00"))
        assertNull(AlbumKeys.takenAtMillis("not a date"))
    }

    @Test fun audioExtensions() {
        assertEquals("ogg", AlbumKeys.audioExtension("opus"))
        assertEquals("wav", AlbumKeys.audioExtension("wav"))
        assertNull(AlbumKeys.audioExtension("xyz"))
    }
}
