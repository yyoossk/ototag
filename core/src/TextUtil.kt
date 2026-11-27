package com.yyoo.ototag.core

/** Small text cleanups shared by both platforms. */
object LyricsText {
    /**
     * Tames fetched lyrics: CRLF → LF, trailing spaces dropped, runs of
     * blank lines collapsed to one, and the whole text trimmed.
     */
    fun normalize(s: String): String {
        if (s.isBlank()) return s
        val lines = s.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n').map { it.trimEnd() }
        val out = ArrayList<String>(lines.size)
        var blanks = 0
        for (ln in lines) {
            if (ln.isEmpty()) {
                blanks++
                if (blanks <= 1) out.add(ln)
            } else {
                blanks = 0
                out.add(ln)
            }
        }
        return out.joinToString("\n").trim()
    }
}

object SearchText {
    /**
     * Folds a typed search query into a canonical form: full-width and
     * half-width characters unified (NFKC), case folded, and runs of
     * whitespace collapsed — so 「Ｌｅｍｏｎ」 and "lemon" search the same.
     */
    fun normalizeQuery(q: String): String =
        java.text.Normalizer.normalize(q, java.text.Normalizer.Form.NFKC)
            .lowercase().replace(Regex("\\s+"), " ").trim()
}
