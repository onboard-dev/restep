package com.rocketglasses.soberyobratno

/**
 * 日本語専用の音声コマンド判定。
 *
 * Vosk の日本語モデルは単語ごとにスペースを入れて返す（例: "ネジ を 二 本 外し た"）。
 * 日本語の間のスペースを取り除いてから判定・保存する。
 * 文法（grammar）制限は語彙に依存して不安定なため使わず、自由認識の結果を文字列で照合する。
 */
internal object VoiceCommands {
    /** 撮影コマンド。短い発話全体がこのどれかに一致したときだけ撮影する。 */
    val photoCommands = setOf(
        "撮影", "撮影して", "撮影する", "写真撮影", "写真", "写真撮って", "写真を撮って",
        "写真撮る", "写真を撮る", "写真とって", "写真をとって", "しゃしん", "さつえい", "シャッター"
    )

    /** 送信（メモ確定）コマンド。メモの末尾に付けても、単独で言ってもよい。 */
    val sendCommands = setOf("送信", "送信して", "送信する", "メモ送信", "そうしん")

    /** 撮影後に写真をやめる（キャンセル）。説明の一部と区別するため、短い発話全体が一致したときだけ。 */
    val cancelCommands = setOf("キャンセル", "キャンセルして", "キャンセルする")

    /** 「やり直し」: 直後に「た」「て」などが続く普通の文（やり直した 等）は除く。 */
    private val redoRegex = Regex("(やり直し|やりなおし)(?![たてをがはのにでも])")
    private val redoEndRegex = Regex("(やり直し|やりなおし)$")
    fun containsRedo(text: String) = redoRegex.containsMatchIn(normalize(text))
    fun endsWithRedo(text: String) = redoEndRegex.containsMatchIn(stripPunctuation(normalize(text)))
    /** 最後の「やり直し」より後ろの文字だけ返す（なければ元のまま）。 */
    fun afterLastRedo(text: String): String {
        val clean = normalize(text)
        val last = redoRegex.findAll(clean).lastOrNull() ?: return clean
        return clean.substring(last.range.last + 1)
    }

    private val endings = sendCommands.sortedByDescending { it.length }
    data class Submission(val note: String, val send: Boolean)

    private fun isAscii(c: Char) = c.code < 128

    /** 日本語文字に挟まれたスペースを削除し、英数字どうしの間のスペースだけ残す。 */
    fun normalize(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = StringBuilder()
        for (w in words) {
            if (out.isNotEmpty() && isAscii(out.last()) && isAscii(w.first())) out.append(' ')
            out.append(w)
        }
        return out.toString()
    }

    private fun stripPunctuation(text: String) = text.trimEnd('。', '、', '.', ',', '!', '！', ' ')

    fun submission(text: String): Submission {
        val clean = stripPunctuation(normalize(text))
        for (ending in endings) {
            if (clean == ending) return Submission("", true)
            if (clean.endsWith(ending)) return Submission(stripPunctuation(clean.dropLast(ending.length)), true)
        }
        return Submission(clean, false)
    }

    fun isCancelCommand(text: String): Boolean = stripPunctuation(normalize(text)) in cancelCommands

    fun isPhotoCommand(text: String): Boolean {
        val clean = stripPunctuation(normalize(text))
        return clean in photoCommands
    }
}
