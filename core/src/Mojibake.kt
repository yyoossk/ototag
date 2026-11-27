package com.yyoo.ototag.core

import java.nio.charset.Charset

/**
 * Repairs the classic Japanese mojibake: text that was encoded in
 * Shift_JIS (typical of ID3v1 tags on old MP3s) but decoded as
 * ISO-8859-1, turning every byte into a Latin-1 character.
 *
 * The repair is deliberately conservative: it only fires when the
 * string has no CJK characters yet, carries Latin-1 supplement
 * characters, and re-decoding those bytes as Windows-31J yields
 * Japanese kana without replacement characters. Anything else —
 * plain ASCII, real European text, already-correct Japanese —
 * is returned unchanged.
 */
object Mojibake {
    fun fix(s: String): String {
        if (s.isEmpty()) return s
        fun Char.isKana() = this in 'ぁ'..'ゖ' || this in 'ァ'..'ヶ' ||
            this in 'ｱ'..'ﾝ'
        if (s.any { it.isKana() || it in '一'..'鿿' }) return s
        if (!s.any { it in ''..'ÿ' }) return s
        return try {
            val decoded = String(
                s.toByteArray(Charset.forName("ISO-8859-1")),
                Charset.forName("windows-31j"))
            if (decoded.any { it.isKana() } && !decoded.contains('�')) decoded else s
        } catch (e: Exception) { s }
    }
}
