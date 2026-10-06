package com.rocketglasses.soberyobratno

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandsTest {
    @Test fun removesSpacesBetweenJapaneseWords() {
        assertEquals("ネジを二本外した", VoiceCommands.normalize("ネジ を 二 本 外し た"))
        assertEquals("M3のネジ", VoiceCommands.normalize("M3 の ネジ"))
        assertEquals("usb cable", VoiceCommands.normalize("usb cable"))
    }

    @Test fun stripsOnlyTrailingSendCommand() {
        assertEquals(VoiceCommands.Submission("ネジを二本外した", true),
            VoiceCommands.submission("ネジ を 二 本 外し た 送信"))
        assertEquals(VoiceCommands.Submission("カバーを外す", true),
            VoiceCommands.submission("カバー を 外す 送信 して"))
        assertEquals(VoiceCommands.Submission("", true), VoiceCommands.submission("送信"))
        assertEquals(VoiceCommands.Submission("", true), VoiceCommands.submission("そう しん"))
        assertEquals(VoiceCommands.Submission("送信機の蓋を開ける", false),
            VoiceCommands.submission("送信 機 の 蓋 を 開ける"))
    }

    @Test fun tapConfirmationKeepsNote() {
        assertEquals(VoiceCommands.Submission("配線を外す", true),
            VoiceCommands.submission("配線 を 外す", commandConfirmed = true))
    }

    @Test fun photoCommandMustBeWholeUtterance() {
        assertTrue(VoiceCommands.isPhotoCommand("撮影"))
        assertTrue(VoiceCommands.isPhotoCommand("写真 を 撮っ て"))
        assertTrue(VoiceCommands.isPhotoCommand("シャッター"))
        assertFalse(VoiceCommands.isPhotoCommand("撮影 した 写真 を 確認"))
        assertFalse(VoiceCommands.isPhotoCommand("ネジ を 外す"))
    }

    @Test fun redoCommand() {
        assertTrue(VoiceCommands.containsRedo("ミカン の 箱 やり直し"))
        assertTrue(VoiceCommands.endsWithRedo("みかん の やり なおし"))
        assertFalse(VoiceCommands.containsRedo("ネジ を やり直し た"))
        assertFalse(VoiceCommands.endsWithRedo("やり直し を 確認"))
        assertEquals("パキラの木送信", VoiceCommands.afterLastRedo("ミカン やり直し やり直し パキラ の 木 送信"))
        assertEquals("ネジ", VoiceCommands.afterLastRedo("ネジ"))
    }
}
