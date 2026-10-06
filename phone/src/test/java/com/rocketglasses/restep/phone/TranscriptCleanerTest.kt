package com.rocketglasses.restep.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptCleanerTest {
    @Test fun removesTrailingSend() {
        assertEquals("ディスプレイ", TranscriptCleaner.clean("ディスプレイ。送信。送信。"))
        assertEquals("玄関の様子", TranscriptCleaner.clean("玄関の様子送信"))
    }
    @Test fun keepsOnlyAfterLastRedo() {
        assertEquals("パキラの木", TranscriptCleaner.clean("ミカンの。やり直し。やり直し。パキラの木。送信。"))
    }
    @Test fun onlyCommandsGivesEmpty() {
        assertEquals("", TranscriptCleaner.clean("ミカンの。やり直し。やり直し。送信。"))
    }
    @Test fun ordinarySentenceWithRedoWordIsKept() {
        assertEquals("ネジをやり直した", TranscriptCleaner.clean("ネジをやり直した。"))
    }
    @Test fun sendInsideSentenceIsKept() {
        assertEquals("送信機の蓋を開ける", TranscriptCleaner.clean("送信機の蓋を開ける。"))
    }
}
