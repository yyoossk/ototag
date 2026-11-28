import com.yyoo.ototag.core.*
import java.io.File
import java.nio.charset.Charset

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
            url.contains("inc=artist-credits") -> File(td, "recording-lookup.json").readBytes()
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
        latest?.windowsZipUrl?.endsWith("OtoTag-Windows.zip") == true &&
        latest?.windowsJarUrl?.endsWith("OtoTag.jar") == true, latest.toString())
    check("isNewer true", UpdateChecker.isNewer("20261127-0644", "20261127-0528"))
    check("isNewer false (same)", !UpdateChecker.isNewer("20261127-0644", "20261127-0644"))
    check("isNewer false (older)", !UpdateChecker.isNewer("20261127-0528", "20261127-0644"))
    // Release notes are published English first, then Japanese after a
    // --- line; the history page shows only the app's language block.
    val bilin = "OtoTag 20261128-0402\n\nEnglish note.\n\n---\n\nOtoTag 20261128-0402\n\nJapanese note."
    check("history body en", UpdateChecker.localizedBody(bilin, "en") == "English note.")
    check("history body fr falls back to en", UpdateChecker.localizedBody(bilin, "fr") == "English note.")
    check("history body ja", UpdateChecker.localizedBody(bilin, "ja") == "Japanese note.")
    check("history body single block", UpdateChecker.localizedBody("OtoTag 20261127-0644\n\nOnly block.", "ja") == "Only block.")
    // Older notes have no --- line: an English block, then a Japanese
    // block. The split is the first line containing Japanese.
    val bilinOld = "English note.\n\nMore English.\n\n日本語の注記。\n\n続き。"
    check("history body old ja", UpdateChecker.localizedBody(bilinOld, "ja") == "日本語の注記。\n\n続き。")
    check("history body old en", UpdateChecker.localizedBody(bilinOld, "en") == "English note.\n\nMore English.")

    // --- custom genre list ---
    check("genre parse", GenreList.parse("Rock\n\n City Pop \nRock") == listOf("Rock", "City Pop"))
    check("genre add", GenreList.add(listOf("Rock"), "My Genre") == listOf("Rock", "My Genre"))
    check("genre add dup", GenreList.add(listOf("Rock"), "Rock") == listOf("Rock"))
    check("genre add builtin", GenreList.add(emptyList(), "Rock") == emptyList<String>())
    check("genre add blank", GenreList.add(listOf("Rock"), "  ") == listOf("Rock"))
    check("genre remove", GenreList.remove(listOf("Rock", "City Pop"), "Rock") == listOf("City Pop"))
    check("genre roundtrip", GenreList.parse(GenreList.serialize(listOf("A", "B"))) == listOf("A", "B"))
    check("genre defaults have jp", TagData.GENRES.contains("アニソン") && TagData.GENRES.contains("ゲーム"))
    check("genre visible defaults", GenreList.visibleDefaults(listOf("Rock")).size == TagData.GENRES.size - 1 &&
        !GenreList.visibleDefaults(listOf("Rock")).contains("Rock"))
    check("genre hide", GenreList.hide(emptyList(), "Rock") == listOf("Rock"))
    check("genre hide custom no-op", GenreList.hide(emptyList(), "City Pop X") == emptyList<String>())
    check("genre unhide", GenreList.unhide(listOf("Rock"), "Rock") == emptyList<String>())
    check("genre trash", GenreList.trash(emptyList(), "My Genre") == listOf("My Genre"))
    check("genre trash dup", GenreList.trash(listOf("My Genre"), "My Genre") == listOf("My Genre"))

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

    // --- WAV / AIFF / DSF roundtrip (fixtures are tiny generated files) ---
    for ((fname, label) in listOf("small.wav" to "wav", "small.aiff" to "aiff", "small.dsf" to "dsf")) {
        val src = File("testdata/$fname")
        val work = File("testwork/$fname").apply { parentFile.mkdirs() }
        src.copyTo(work, overwrite = true)
        TagService.write(work, TagData(title = "形式テスト", artist = "歌手", album = "盤",
            year = "2024", track = "2", genre = "J-Pop"))
        val r = TagService.read(work)
        check("$label roundtrip", r.title == "形式テスト" && r.artist == "歌手" && r.track == "2",
            "${r.title}/${r.artist}/${r.track}")
        check("$label sniff", TagService.sniffFormat(work) != null, TagService.sniffFormat(work).toString())
    }

    // --- ID3v1-only MP3: writing must upgrade to ID3v2, not fail ---
    // (A real file with no ID3v2 header and an empty ID3v1 trailer made
    // every save throw "FieldKey TRACK_TOTAL is not supported by ID3v1".)
    run {
        var raw = File("testdata/small.mp3").readBytes()
        // Strip the ID3v2 header, if any (syncsafe size in bytes 6..9).
        if (raw.size > 10 && raw[0] == 'I'.code.toByte() && raw[1] == 'D'.code.toByte()) {
            val sz = ((raw[6].toInt() and 0x7F) shl 21) or ((raw[7].toInt() and 0x7F) shl 14) or
                ((raw[8].toInt() and 0x7F) shl 7) or (raw[9].toInt() and 0x7F)
            raw = raw.copyOfRange(10 + sz, raw.size)
        }
        // Strip an existing ID3v1 trailer, if any.
        if (raw.size > 128 && raw[raw.size - 128] == 'T'.code.toByte() &&
            raw[raw.size - 127] == 'A'.code.toByte() && raw[raw.size - 126] == 'G'.code.toByte())
            raw = raw.copyOfRange(0, raw.size - 128)
        val v1 = ByteArray(128)
        "TAG".toByteArray(Charsets.US_ASCII).copyInto(v1, 0)
        "V1 Song".toByteArray(Charsets.US_ASCII).copyInto(v1, 3)
        "V1 Artist".toByteArray(Charsets.US_ASCII).copyInto(v1, 33)
        v1[127] = 255.toByte()
        val work = File("testwork/v1only.mp3").apply { parentFile.mkdirs() }
        work.writeBytes(raw + v1)
        val before = TagService.read(work)
        check("id3v1 read", before.title == "V1 Song" && before.artist == "V1 Artist",
            "${before.title}/${before.artist}")
        TagService.write(work, TagData(title = "V1 Song", artist = "V1 Artist",
            album = "盤", track = "2", trackTotal = "12", disc = "1", discTotal = "2",
            lyrics = "歌詞です", cover = File("testdata/tiny.png").readBytes()))
        val after = TagService.read(work)
        check("id3v1 upgrade write", after.trackTotal == "12" && after.discTotal == "2" &&
            after.title == "V1 Song" && after.lyrics == "歌詞です" && after.cover != null,
            "${after.trackTotal}/${after.discTotal}/${after.lyrics}/${after.cover != null}")
        val head = work.readBytes()
        check("id3v1 became id3v2", head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte(), "")
    }

    // --- Strip leading track numbers from file names ---
    check("strip dot", Renamer.stripTrackPrefix("01. Lemon") == "Lemon",
        Renamer.stripTrackPrefix("01. Lemon"))
    check("strip space", Renamer.stripTrackPrefix("02 アイドル") == "アイドル",
        Renamer.stripTrackPrefix("02 アイドル"))
    check("strip dash", Renamer.stripTrackPrefix("1- Intro") == "Intro",
        Renamer.stripTrackPrefix("1- Intro"))
    check("strip under", Renamer.stripTrackPrefix("03_残響散歌") == "残響散歌",
        Renamer.stripTrackPrefix("03_残響散歌"))
    check("strip paren", Renamer.stripTrackPrefix("12) Song") == "Song",
        Renamer.stripTrackPrefix("12) Song"))
    check("strip disc-track", Renamer.stripTrackPrefix("01-02 Duet") == "Duet",
        Renamer.stripTrackPrefix("01-02 Duet"))
    check("strip keeps plain", Renamer.stripTrackPrefix("Lemon") == "Lemon", "")
    check("strip keeps 24K", Renamer.stripTrackPrefix("24K Magic") == "24K Magic",
        Renamer.stripTrackPrefix("24K Magic"))
    check("strip keeps year", Renamer.stripTrackPrefix("2024 Song") == "2024 Song",
        Renamer.stripTrackPrefix("2024 Song"))
    check("strip keeps number only", Renamer.stripTrackPrefix("365") == "365",
        Renamer.stripTrackPrefix("365"))

    // --- Mojibake repair (Shift_JIS bytes mis-decoded as Latin-1) ---
    val original = "こんにちは世界"
    val garbled = String(original.toByteArray(Charset.forName("windows-31j")),
        Charset.forName("ISO-8859-1"))
    check("mojibake repaired", Mojibake.fix(garbled) == original, Mojibake.fix(garbled))
    check("mojibake keeps japanese", Mojibake.fix(original) == original, "")
    check("mojibake keeps ascii", Mojibake.fix("Hello World") == "Hello World", "")
    check("mojibake keeps european", Mojibake.fix("Résumé") == "Résumé", Mojibake.fix("Résumé"))

    // --- Backup pruning keeps the newest few per file ---
    val bdir = File("testwork/backups")
    val sub = File(bdir, "deadbeef"); sub.mkdirs()
    for (i in 1..6) File(sub, "100000000000${i}_song.mp3").writeText("x")
    val deleted = Backup.prune(bdir, keepPerFile = 3)
    // java.util sort on purpose: this suite also runs against the shrunk
    // desktop jar, where stdlib helpers no production code uses are gone.
    val remaining = (sub.listFiles() ?: emptyArray()).map { it.name }
        .also { java.util.Collections.sort(it) }
    check("backup prune", deleted == 3 && remaining.size == 3 &&
        remaining.first().startsWith("1000000000004"), remaining.toString())

    // --- Update history (fixture built from the real published releases) ---
    UpdateChecker.fetcher = { File("testdata/github-releases.json").readBytes() }
    val rels = UpdateChecker.fetchReleases()
    check("releases parsed", rels.size == 2 && rels[0].tag == "20261127-2346" &&
        rels[0].publishedAt == "2026-11-27" && rels[0].body.contains("Musixmatch") &&
        rels[1].tag == "20261127-2335", rels.size.toString())

    // --- AcoustID lookup + recording lookup (fixtures) ---
    AcoustIdClient.fetcher = { url ->
        check("acoustid url", url.contains("api.acoustid.org/v2/lookup") &&
            url.contains("meta=recordingids") && url.contains("duration=215"), url)
        File("testdata/acoustid-lookup.json").readBytes()
    }
    val aids = AcoustIdClient.lookupRecordingIds("AQADtEqSJEk", 215, "TESTKEY")
    check("acoustid ids", aids == listOf(
        "9362e22b-9a1b-4621-8b6d-a0420f24a784",
        "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"), aids.toString())
    MbClient.fetcher = { url ->
        when {
            url.contains("inc=artist-credits") -> File("testdata/recording-lookup.json").readBytes()
            url.contains("/ws/2/recording/") -> File("testdata/search.json").readBytes()
            url.contains("/ws/2/release/") -> File("testdata/release.json").readBytes()
            url.contains("coverartarchive.org") -> File("testdata/cover.jpg").readBytes()
            else -> throw IllegalStateException("unexpected url $url")
        }
    }
    val looked = MbClient.lookupRecording("9362e22b-9a1b-4621-8b6d-a0420f24a784")
    check("mb lookup title", looked?.title == "Lemon", looked?.title ?: "null")
    check("mb lookup releases", (looked?.releases?.size ?: 0) > 0,
        (looked?.releases?.size ?: -1).toString())
    Strings.lang = "ja"
    check("acoustid strings", Strings.t("acoustid_key") == "AcoustID APIキー" &&
        Strings.t("fp_search") == "指紋で検索")

    // --- backup generations + restore-at (item: choose which to restore) ---
    run {
        val dir = File("testwork/gens"); dir.deleteRecursively(); dir.mkdirs()
        val bdir = File("testwork/genbackups"); bdir.deleteRecursively(); bdir.mkdirs()
        val song = File(dir, "song.mp3"); song.writeText("v3")
        val first = Backup.backup(song, bdir)
        val folder = first.parentFile
        File(folder, "1000_song.mp3").writeText("v1")
        File(folder, "2000_song.mp3").writeText("v2")
        val gens = Backup.generations(song, bdir)
        check("generations", gens.size == 3 && gens[0] > 2000L &&
            gens[1] == 2000L && gens[2] == 1000L, gens.toString())
        check("restoreAt 2000", Backup.restoreAt(song, bdir, 2000L) &&
            String(song.readBytes(), Charsets.UTF_8) == "v2", String(song.readBytes(), Charsets.UTF_8))
        check("restoreAt 1500", Backup.restoreAt(song, bdir, 1500L) &&
            String(song.readBytes(), Charsets.UTF_8) == "v1", String(song.readBytes(), Charsets.UTF_8))
        check("restoreAt 500 none", !Backup.restoreAt(song, bdir, 500L))
    }

    // --- tag diff preview lines ---
    run {
        Strings.lang = "ja"
        val old = TagData(title = "Old", artist = "A", album = "X", track = "1")
        val new = TagData(title = "New", artist = "A", album = "X", track = "3",
            trackTotal = "12", cover = byteArrayOf(1, 2, 3))
        val lines = TagDiff.lines(old, new)
        check("diff lines", lines.size == 3 &&
            lines.any { it.contains("Old") && it.contains("New") } &&
            lines.any { it.startsWith(Strings.t("track")) } &&
            lines.any { it.startsWith(Strings.t("cover")) }, lines.toString())
        check("diff none", TagDiff.lines(old, old.copy()).isEmpty())
    }

    // --- backup size + delete-all (settings) ---
    run {
        val bdir = File("testwork/sizebackups"); bdir.deleteRecursively()
        val song = File("testwork/gens/song.mp3")
        Backup.backup(song, bdir); Backup.backup(song, bdir)
        val sz = Backup.totalSize(bdir)
        check("backup size", sz > 0, sz.toString())
        val n = Backup.deleteAll(bdir)
        check("backup deleteAll", n >= 2 && Backup.totalSize(bdir) == 0L &&
            Backup.generations(song, bdir).isEmpty(), n.toString())
        Strings.lang = "ja"
        check("filter/backup strings", Strings.t("filter_error") == "エラー" &&
            Strings.t("delete_all") == "全て削除")
        check("sort/url strings", Strings.t("sort_updated") == "更新日時順" &&
            Strings.t("from_url") == "URLから" && Strings.t("cover_url") == "カバー画像のURL")
        check("genre list", TagData.GENRES.size >= 20 && "J-Pop" in TagData.GENRES)
    }

    // --- tag-snapshot backups: undo restores tags + lyrics + cover ---
    run {
        val bdir = File("testwork/snapbackups"); bdir.deleteRecursively()
        val src = File("../spike/audio/test.mp3")
        val song = File("testwork/snap-song.mp3"); src.copyTo(song, overwrite = true)
        // Start from a known tag state (title, lyrics and a real cover).
        TagService.write(song, TagService.read(song).copy(
            title = "元のタイトル", lyrics = "元の歌詞",
            cover = File("testdata/tiny.png").readBytes()))
        val before = TagService.read(song)
        val snapFile = Backup.backup(song, bdir)
        check("snapshot is small", snapFile.length() < song.length(),
            "${snapFile.length()} vs ${song.length()}")
        val changed = before.copy(
            title = "書き換えたタイトル", lyrics = "別の歌詞", cover = null)
        TagService.write(song, changed)
        val mid = TagService.read(song)
        check("tags changed before undo", mid.title == "書き換えたタイトル" && mid.cover == null)
        check("snapshot restore ok", Backup.restore(song, bdir))
        val after = TagService.read(song)
        check("snapshot restores tags", after.title == before.title &&
            after.lyrics == before.lyrics &&
            (after.cover?.size ?: -1) == (before.cover?.size ?: -2),
            "title=${after.title} lyrics=${after.lyrics.take(10)} cover=${after.cover?.size}")
        // Legacy whole-file backups (older builds) still restore by copy.
        val legacyDir = File("testwork/legacybackups"); legacyDir.deleteRecursively()
        val key = Integer.toHexString(song.absolutePath.hashCode())
        val ldir = File(legacyDir, key); ldir.mkdirs()
        val raw = File(ldir, "1234567890000_" + song.name)
        src.copyTo(raw, overwrite = true)
        TagService.write(song, before.copy(title = "また変更"))
        check("legacy restore ok", Backup.restore(song, legacyDir) &&
            song.readBytes().contentEquals(src.readBytes()))
        song.delete()
    }

    // --- backup: cover dedupe, prune keeps 1, cover dims, text utils ---
    run {
        val bdir = File("testwork/dedupebackups"); bdir.deleteRecursively()
        val src = File("../spike/audio/test.mp3")
        val song = File("testwork/dedupe-song.mp3"); src.copyTo(song, overwrite = true)
        val coverBytes = File("testdata/tiny.png").readBytes()
        TagService.write(song, TagService.read(song).copy(
            title = "タイトルA", cover = coverBytes))
        val withCover = TagService.read(song)
        check("cover dims", withCover.coverW == 1 && withCover.coverH == 1,
            "${withCover.coverW}x${withCover.coverH}")
        val snap1 = Backup.backup(song, bdir)
        TagService.write(song, TagService.read(song).copy(title = "タイトルB"))
        val snap2 = Backup.backup(song, bdir)
        fun containsSub(hay: ByteArray, needle: ByteArray): Boolean {
            if (needle.isEmpty() || hay.size < needle.size) return false
            outer@ for (i in 0..hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return true
            }
            return false
        }
        check("first snapshot holds cover", containsSub(snap1.readBytes(), coverBytes))
        check("second snapshot borrows cover", !containsSub(snap2.readBytes(), coverBytes) &&
            snap2.length() < snap1.length(), "${snap2.length()} vs ${snap1.length()}")
        TagService.write(song, TagService.read(song).copy(title = "タイトルC", cover = null))
        check("dedupe restore ok", Backup.restore(song, bdir))
        val restored = TagService.read(song)
        check("dedupe restore values", restored.title == "タイトルB" &&
            (restored.cover?.size ?: -1) == coverBytes.size,
            "title=${restored.title} cover=${restored.cover?.size}")
        Backup.prune(bdir)
        check("prune keeps cover chain", snap1.exists() && snap2.exists() &&
            Backup.generations(song, bdir).size == 2)
        check("restore after prune", Backup.restore(song, bdir) &&
            TagService.read(song).cover != null)
        // Plain prune: with no cover references only the newest survives.
        val pdir = File("testwork/prune1"); pdir.deleteRecursively()
        val txt = File("testwork/prune1.txt"); txt.writeText("x")
        Backup.backup(txt, pdir); Thread.sleep(5)
        Backup.backup(txt, pdir); Thread.sleep(5)
        Backup.backup(txt, pdir)
        Backup.prune(pdir)
        check("prune default keeps 1", Backup.generations(txt, pdir).size == 1,
            Backup.generations(txt, pdir).toString())
        check("lyrics normalize", LyricsText.normalize("a\r\n\r\n\r\nb  \n\n") == "a\n\nb",
            LyricsText.normalize("a\r\n\r\n\r\nb  \n\n").replace("\n", "|"))
        check("query normalize", SearchText.normalizeQuery("Ｌｅｍｏｎ　 米津 ") == "lemon 米津",
            SearchText.normalizeQuery("Ｌｅｍｏｎ　 米津 "))
        check("new strings", Strings.t("help_title") == "使い方" &&
            Strings.t("include_subfolders") == "サブフォルダも含める" &&
            Strings.t("from_clipboard") == "クリップボードから" &&
            Strings.t("back_to_top") == "先頭へ")
        song.delete(); txt.delete()
    }

    println(if (failures == 0) "ALL PASS" else "FAILURES: $failures")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

