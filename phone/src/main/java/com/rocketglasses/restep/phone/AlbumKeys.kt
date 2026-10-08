package com.rocketglasses.restep.phone

import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

/**
 * アルバムから取り込む StepMemo の写真の識別値（画面や通信に依存しない）。
 * 同じ写真を何度取り込んでも同じ ID になるので、二重に取り込まない。
 */
object AlbumKeys {
    /** グラスの作業ID からプロジェクトの ID を作る（旧同期の ID と重ならないよう名前を付けて変換）。 */
    fun sessionId(glassesSessionId: String): String =
        UUID.nameUUIDFromBytes("stepmemo-session:$glassesSessionId".toByteArray(Charsets.UTF_8)).toString()

    /** 作業ID・手順番号・ファイルの中身から手順の ID を作る。 */
    fun stepId(glassesSessionId: String, step: Int, sha256: String): String =
        UUID.nameUUIDFromBytes("stepmemo-step:$glassesSessionId:$step:$sha256".toByteArray(Charsets.UTF_8)).toString()

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** XMP の撮影日時（ISO-8601）をミリ秒に。読めなければ null。 */
    fun takenAtMillis(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

    /** 埋め込まれた音声の形式から、保存するファイルの拡張子を決める。知らない形式は null（保存しない）。 */
    fun audioExtension(format: String): String? = when (format) {
        "opus" -> "ogg"
        "wav" -> "wav"
        else -> null
    }
}
