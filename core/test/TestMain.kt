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
            back.lyrics = "テスト歌詞です\n2行目です"
            TagService.write(f, back)
            val backL = TagService.read(f)
            check("lyrics round-trip", backL.lyrics.contains("テスト歌詞です") &&
                backL.lyrics.contains("2行目です"), backL.lyrics.take(30))
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

    // --- iTunes Search API (real response recorded with curl) ---
    ItunesClient.fetcher = { url ->
        when {
            url.contains("itunes.apple.com/search") -> File("testdata/itunes-search.json").readBytes()
            url.contains("mzstatic.com") -> File("testdata/cover.jpg").readBytes()
            else -> throw IllegalStateException("unexpected url $url")
        }
    }
    val itu = ItunesClient.search("YOASOBI アイドル")
    check("itunes count", itu.isNotEmpty(), itu.size.toString())
    val idolT = itu.firstOrNull()
    check("itunes idol fields", idolT?.title == "アイドル" && idolT?.artist == "YOASOBI" &&
        idolT?.album == "アイドル - Single" && idolT?.year == "2023" && idolT?.genre == "J-Pop" &&
        idolT?.track == "1", idolT.toString())
    check("itunes artwork upgraded to 600", idolT?.artworkUrl?.contains("600x600bb.jpg") == true, idolT?.artworkUrl ?: "")
    val itTag = ItunesClient.buildTag(idolT!!)
    check("itunes tag + cover", itTag.title == "アイドル" && itTag.cover != null && itTag.cover!!.size > 1000 && !itTag.coverFetchFailed)

    // --- session search cache: the same query must not fetch twice ---
    var ituFetches = 0
    ItunesClient.fetcher = { url ->
        if (url.contains("itunes.apple.com")) { ituFetches++; File("testdata/itunes-search.json").readBytes() }
        else File("testdata/cover.jpg").readBytes()
    }
    ItunesClient.search("cache probe song")
    ItunesClient.search("cache probe song")
    ItunesClient.search("Cache Probe Song")
    check("search cache hit", ituFetches == 1, ituFetches.toString())

    // --- self-update check (real GitHub API response recorded with curl) ---
    UpdateChecker.fetcher = { url ->
        if (url.contains("api.github.com")) File("testdata/gh-latest.json").readBytes()
        else throw IllegalStateException("unexpected url $url")
    }
    val latest = UpdateChecker.fetchLatest()
    check("update tag", latest?.tag == "20261127-0644", latest?.tag ?: "null")
    check("update asset urls", latest?.androidApkUrl?.endsWith("OtoTag-Android.apk") == true &&
        latest?.windowsZipUrl?.endsWith("OtoTag-Windows.zip") == true, latest.toString())
    check("isNewer true", UpdateChecker.isNewer("20261127-0644", "20261127-0528"))
    check("isNewer false (same)", !UpdateChecker.isNewer("20261127-0644", "20261127-0644"))
    check("isNewer false (older)", !UpdateChecker.isNewer("20261127-0528", "20261127-0644"))

    // --- disguised container: a real MP4 carrying an .mp3 name (YouTube download) ---
    val work2 = File("testwork"); work2.mkdirs()
    val realM4a = File(work2, "real.m4a")
    File("../samples/03. 残響散歌.m4a").copyTo(realM4a, overwrite = true)
    TagService.write(realM4a, TagData(title = "Doppelgänger", artist = "Creepy Nuts"))
    val disguised = File(work2, "disguised.mp3")
    realM4a.copyTo(disguised, overwrite = true)
    check("sniff mp4-in-mp3", TagService.sniffFormat(disguised) ==
        org.jaudiotagger.kt.AudioFormat.MP4, TagService.sniffFormat(disguised).toString())
    val dRead = TagService.read(disguised)
    check("disguised read", dRead.title == "Doppelgänger" && dRead.artist == "Creepy Nuts", dRead.title)
    TagService.write(disguised, TagData(title = "Doppelgänger", artist = "Creepy Nuts", album = "LEGION"))
    val dBack = TagService.read(disguised)
    check("disguised write + verify", dBack.album == "LEGION", dBack.album)
    val head = disguised.readBytes().copyOfRange(4, 8).toString(Charsets.US_ASCII)
    check("disguised stays mp4", head == "ftyp", head)

    // --- free-text search (user-typed query) over the recorded MB response ---
    MbClient.fetcher = { url ->
        when {
            url.contains("musicbrainz.org/ws/2/recording") -> File("testdata/search.json").readBytes()
            else -> throw IllegalStateException("unexpected url $url")
        }
    }
    val free = MbClient.searchFreeText("Lemon 米津玄師")
    check("free-text search", free.isNotEmpty(), free.size.toString())

    // --- Deezer (real recorded search + cover; album fixture in documented shape) ---
    DeezerClient.fetcher = { url ->
        when {
            url.contains("/album/") -> File("testdata/deezer-album.json").readBytes()
            url.contains("api.deezer.com/search") -> File("testdata/deezer-search.json").readBytes()
            url.contains("dzcdn.net") -> File("testdata/deezer-cover.jpg").readBytes()
            else -> throw IllegalStateException("unexpected url $url")
        }
    }
    val dz = DeezerClient.search("YOASOBI")
    check("deezer count", dz.isNotEmpty(), dz.size.toString())
    val dzT = dz.firstOrNull()
    check("deezer fields", dzT?.title == "夜に駆ける" && dzT?.artist == "YOASOBI" &&
        dzT?.album == "夜に駆ける" && dzT?.isrc == "JPP301900716" &&
        dzT?.coverUrl?.contains("1000x1000") == true, dzT.toString())
    val dzTag = DeezerClient.buildTag(dzT!!)
    check("deezer tag + album details + cover", dzTag.title == "夜に駆ける" &&
        dzTag.year == "2019" && dzTag.track == "1" && dzTag.trackTotal == "1" &&
        dzTag.cover != null && dzTag.cover!!.size > 1000 && !dzTag.coverFetchFailed,
        "year=${dzTag.year} track=${dzTag.track}/${dzTag.trackTotal} cover=${dzTag.cover?.size}")

    // --- VGMdb (fixtures in the documented vgmdb.info shape) ---
    VgmdbClient.fetcher = { url ->
        when {
            url.contains("/search") -> File("testdata/vgmdb-search.json").readBytes()
            url.contains("/album/") -> File("testdata/vgmdb-album.json").readBytes()
            url.contains("example.invalid") -> File("testdata/cover.jpg").readBytes()
            else -> throw IllegalStateException("unexpected url $url")
        }
    }
    val vg = VgmdbClient.search("sample")
    check("vgmdb tracks", vg.size == 2, vg.size.toString())
    val vgT = vg.firstOrNull()
    check("vgmdb thumb", vgT?.coverThumbUrl == "https://example.invalid/cover-small.jpg",
        vgT?.coverThumbUrl ?: "")
    check("vgmdb fields", vgT?.title == "サンプルの歌" && vgT?.artist == "サンプル歌手" &&
        vgT?.album == "サンプル・サウンドトラック" && vgT?.year == "2023" &&
        vgT?.track == "1" && vgT?.trackTotal == "2", vgT.toString())
    val vgTag = VgmdbClient.buildTag(vgT!!)
    check("vgmdb tag + cover", vgTag.title == "サンプルの歌" && vgTag.cover != null &&
        vgTag.cover!!.size > 1000 && !vgTag.coverFetchFailed)

    // ---- rename works on the file name alone (never on tag contents) ----
    val rp = NameParser.parse(java.io.File("08. IN MY DREAM (1).mp3"))
    val nd = Renamer.dataFromName(rp)
    check("rename keeps filename track", nd.track == "08", nd.track)
    val rout = Renamer.applyPattern("%track% - %title%", nd, "x")
    check("rename pattern starts from name", rout.startsWith("08 - IN MY DREAM"), rout)

    // --- Audius (recorded real response) ---
    AudiusClient.fetcher = { url ->
        if (url.contains("/v1/tracks/search")) File("testdata/audius-search.json").readBytes()
        else byteArrayOf(9, 9, 9, 9, 9)
    }
    val au = AudiusClient.search("lemon")
    val auT = au.firstOrNull()
    check("audius results", au.isNotEmpty(), au.size.toString())
    check("audius fields", auT?.title?.startsWith("Bitcoin Bully") == true &&
        auT?.artist == "Block Bully 638" && auT?.genre == "Hip-Hop/Rap" &&
        auT?.year == "2021" && auT?.coverThumbUrl?.contains("150x150") == true,
        "${auT?.title} / ${auT?.artist}")
    val auTag = auT?.let { AudiusClient.buildTag(it) }
    check("audius partial tag", auTag?.partial == true && auTag?.album == "" &&
        auTag?.cover?.size == 5, "")

    // --- NDL Search (recorded real response, sound recordings only) ---
    NdlClient.fetcher = { File("testdata/ndl-search.xml").readBytes() }
    val ndl = NdlClient.search("炎 LiSA")
    val ndlR = ndl.firstOrNull()
    check("ndl results", ndl.isNotEmpty(), ndl.size.toString())
    check("ndl fields", ndlR?.album == "炎" && ndlR?.artist == "LiSA" &&
        ndlR?.year == "2020" && ndlR?.publisher == "Sony Music Labels",
        "${ndlR?.album} / ${ndlR?.artist} / ${ndlR?.year}")
    check("ndl partial tag", ndlR?.let { NdlClient.buildTag(it) }?.partial == true, "")

    // --- partial merge keeps unknown fields ---
    val merged = TagData(album = "新しいアルバム", partial = true)
        .mergedOver(TagData(title = "曲名", album = "古いアルバム", track = "3"))
    check("mergedOver", merged.title == "曲名" && merged.album == "新しいアルバム" &&
        merged.track == "3" && !merged.partial, "")

    // --- LRCLIB (recorded real response) ---
    LrclibClient.fetcher = { File("testdata/lrclib-search.json").readBytes() }
    val lrc = LrclibClient.fetchBest("Lemon", "米津玄師", "", 255)
    check("lrclib best", lrc != null && lrc.plainLyrics.startsWith("夢ならば"),
        lrc?.trackName ?: "null")
    check("lrclib none", LrclibClient.search("", "", "").isEmpty(), "")

    // --- Musixmatch (documented response shape; live 401 shape for bad key) ---
    MusixmatchClient.fetcher = { url ->
        if (url.contains("apikey=BADKEY"))
            """{"message":{"header":{"status_code":401,"execute_time":0.03},"body":[]}}"""
                .toByteArray(Charsets.UTF_8)
        else File("testdata/musixmatch-lyrics.json").readBytes()
    }
    MusixmatchClient.apiKey = ""
    check("mxm needs key", MusixmatchClient.fetchLyrics("Lemon", "米津玄師") == null, "")
    MusixmatchClient.apiKey = "TESTKEY"
    val mxm = MusixmatchClient.fetchLyrics("Lemon", "米津玄師")
    check("mxm lyrics", mxm != null && mxm.startsWith("夢ならば") &&
        !mxm.contains("*******") && !mxm.contains("(1234567)"), mxm?.take(20) ?: "null")
    MusixmatchClient.apiKey = "BADKEY"
    check("mxm bad key", MusixmatchClient.fetchLyrics("Lemon", "米津玄師") == null, "")

    // --- Lyrics.ovh (recorded real response) ---
    LyricsOvhClient.fetcher = { url ->
        if (url.contains("Queen")) File("testdata/lyricsovh.json").readBytes()
        else throw IllegalStateException("HTTP 404")
    }
    val ovh = LyricsOvhClient.fetchLyrics("Queen", "Bohemian Rhapsody")
    check("lyricsovh found", ovh != null && ovh.startsWith("Is this the real life"),
        ovh?.take(20) ?: "null")
    check("lyricsovh missing", LyricsOvhClient.fetchLyrics("Nobody", "Nothing") == null, "")

    // --- Update history (fixture built from the real published releases) ---
    UpdateChecker.fetcher = { File("testdata/github-releases.json").readBytes() }
    val rels = UpdateChecker.fetchReleases()
    check("releases parsed", rels.size == 2 && rels[0].tag == "20261127-2346" &&
        rels[0].publishedAt == "2026-11-27" && rels[0].body.contains("Musixmatch") &&
        rels[1].tag == "20261127-2335", rels.size.toString())

    println(if (failures == 0) "ALL PASS" else "FAILURES: $failures")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

