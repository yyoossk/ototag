import com.yyoo.ototag.core.*
import java.io.File

var failures = 0
fun check(name: String, cond: Boolean, detail: String = "") {
    if (!cond) failures++
    println("[${if (cond) "OK" else "FAIL"}] $name $detail")
}

fun main() {
    // VM-only: this dev sandbox blocks direct TCP from Java, so the MusicBrainz
    // flow is exercised against REAL API responses recorded with curl into testdata/.
    // The parsing / candidate / build / apply code under test is the production code.
    MbClient.fetcher = { url ->
        val td = File("testdata")
        when {
            url.contains("/ws/2/recording/") -> File(td, "search.json").readBytes()
            url.contains("/ws/2/release/") -> File(td, "release.json").readBytes()
            url.contains("coverartarchive.org") -> File(td, "cover.jpg").readBytes()
            else -> throw IllegalStateException("unexpected url $url")
        }
    }
    // --- parser ---
    val p1 = NameParser.parse(File("/music/米津玄師/LOST CORNER/01 - 米津玄師 - Lemon.mp3"))
    check("parse track", p1.trackNo == "01", p1.toString())
    check("parse artist", p1.artist == "米津玄師", p1.toString())
    check("parse title", p1.title == "Lemon", p1.toString())
    val p2 = NameParser.parse(File("/music/03. 打上花火 - DAOKO.mp3"))
    // default interpretation is artist - title; the swapped query variant must recover title/artist
    check("parse2 default", p2.trackNo == "03" && p2.artist == "打上花火" && p2.title == "DAOKO", p2.toString())
    check("parse2 swap query", NameParser.queries(p2).any { it.first == "打上花火" && it.second == "DAOKO" })
    val p3 = NameParser.parse(File("/music/unknown [320kbps].mp3"))
    check("parse noise", p3.title == "unknown", p3.toString())

    // --- renamer ---
    check("sanitize", Renamer.sanitize("a/b:c*d?e\"f<g>h|i") == "a b c d e f g h i", Renamer.sanitize("a/b:c*d?e\"f<g>h|i"))
    val rd = TagData(title = "Lemon", artist = "米津玄師", track = "3")
    check("pattern", Renamer.applyPattern("%track% - %title%", rd, "x") == "03 - Lemon")

    // --- i18n ---
    check("lang resolve ja", Strings.resolve(null, "ja-JP") == "ja")
    check("lang resolve zh-TW", Strings.resolve(null, "zh-TW") == "zh-Hant")
    check("lang resolve saved", Strings.resolve("fr", "ja") == "fr")
    Strings.lang = "fr"; check("fr edit", Strings.t("edit") == "Modifier")
    Strings.lang = "ko"; check("ko edit", Strings.t("edit") == "편집")
    Strings.lang = "ja"; check("ja edit", Strings.t("edit") == "編集")
    for (l in Strings.LANGS) {
        Strings.lang = l
        val missing = listOf("edit", "save", "search_apply", "rename", "title").filter { Strings.t(it) == it }
        check("i18n complete $l", missing.isEmpty(), missing.toString())
    }
    Strings.lang = "ja"

    // --- MusicBrainz live: from filename ---
    val cands = LinkedHashMap<String, MbCandidate>()
    for ((t, a, al) in NameParser.queries(p1)) {
        try {
            for (c in MbClient.searchByTagsOrName(t, a, al)) cands.putIfAbsent(c.recordingId, c)
        } catch (e: Exception) { e.printStackTrace() }
        if (cands.isNotEmpty()) break
    }
    check("mb candidates", cands.isNotEmpty(), "count=${cands.size}")
    val lemon = cands.values.firstOrNull { c ->
        c.title == "Lemon" && c.releases.any { it.id == "b92cfc89-bb7e-47dd-948f-f125ed145370" }
    } ?: cands.values.firstOrNull { it.title == "Lemon" }
    check("mb Lemon found", lemon != null, cands.values.take(3).map { it.title }.toString())
    if (lemon != null) {
        // the recorded release.json is the Lemon single b92cfc89 (with cover art)
        val rel = lemon.releases.firstOrNull { it.id == "b92cfc89-bb7e-47dd-948f-f125ed145370" }
            ?: lemon.releases.firstOrNull { it.trackPos > 0 } ?: lemon.releases.firstOrNull()
        check("mb release exists", rel != null)
        if (rel != null) {
            val tag = MbClient.buildTag(lemon, rel)
            println("built tag: title=${tag.title} artist=${tag.artist} album=${tag.album} year=${tag.year} track=${tag.track}/${tag.trackTotal} genre=${tag.genre} cover=${tag.cover?.size}")
            check("built album", tag.album.isNotBlank())
            check("built track", tag.track.isNotBlank() && tag.track != "0")
            check("built cover", tag.cover != null && tag.cover!!.size > 1000, "size=${tag.cover?.size}")

            // --- apply to a real file, verify, rename ---
            val work = File("testwork"); work.mkdirs()
            val src = File("../spike/audio/test.mp3")
            val f = File(work, "01 - 米津玄師 - Lemon.mp3")
            src.copyTo(f, overwrite = true)
            Backup.backup(f, File(work, "backups"))
            TagService.write(f, tag)
            val back = TagService.read(f)
            check("applied title", back.title == "Lemon", back.title)
            check("applied artist", back.artist.contains("米津玄師"), back.artist)
            check("applied mbid (UFID workaround)", back.mbRecordingId == lemon.recordingId, back.mbRecordingId)
            check("applied cover", back.cover != null && back.cover!!.size > 1000)
            val newName = Renamer.applyPattern("%track% - %title%", back, f.nameWithoutExtension)
            val renamed = Renamer.rename(f, newName)
            check("renamed", renamed != null && renamed.exists() && renamed.name.startsWith("0"), renamed?.name ?: "null")
            if (renamed != null) {
                Backup.move(f, renamed, File(work, "backups"))
                check("restore backup", Backup.restore(renamed, File(work, "backups")))
                val restored = TagService.read(renamed)
                check("restored is original (no Lemon tag)", restored.title != "Lemon", restored.title)
            }
        }
    }
    // --- AnimeThemes (fixture shaped from the documented REST resources) ---
    AnimeThemesClient.fetcher = { url ->
        if (url.contains("/song")) File("testdata/at-song.json").readBytes()
        else throw IllegalStateException("unexpected url $url")
    }
    val at = AnimeThemesClient.searchSongs("Idol")
    check("at count (1 + 2 slots + orphan)", at.size == 4, at.size.toString())
    val idol = at.firstOrNull { it.title == "Idol" }
    check("at idol artist", idol?.artist == "YOASOBI", idol.toString())
    check("at idol anime/year/label", idol?.anime == "Oshi no Ko" && idol?.year == "2023" && idol?.themeLabel == "OP1", idol.toString())
    val zankyou = at.filter { it.title == "Zankyou Sanka" }
    check("at zankyou two slots", zankyou.size == 2 && zankyou.any { it.themeLabel == "ED2" }, zankyou.toString())
    val tagFromAt = idol!!.toTag()
    check("at tag mapping", tagFromAt.title == "Idol" && tagFromAt.album == "Oshi no Ko" && tagFromAt.comment == "OP1", tagFromAt.toString())

    println(if (failures == 0) "ALL PASS" else "FAILURES: $failures")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
