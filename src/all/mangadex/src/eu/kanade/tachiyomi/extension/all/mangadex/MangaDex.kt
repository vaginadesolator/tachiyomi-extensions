package eu.kanade.tachiyomi.extension.all.mangadex

import android.app.Application
import android.content.SharedPreferences
import android.support.v7.preference.ListPreference
import android.support.v7.preference.PreferenceScreen
import android.util.Log
import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.bool
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.int
import com.github.salomonbrys.kotson.long
import com.github.salomonbrys.kotson.nullString
import com.github.salomonbrys.kotson.string
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.collections.set

abstract class MangaDex(
    override val lang: String,
    private val internalLang: String
) : ConfigurableSource, ParsedHttpSource() {

    override val name = "MangaDex"

    override val baseUrl = "https://www.mangadex.org"

    private val cdnUrl = "https://mangadex.org" // "https://s0.mangadex.org"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val mangadexDescription: MangadexDescription by lazy {
        MangadexDescription(internalLang)
    }

    private val rateLimitInterceptor = RateLimitInterceptor(4)

    override val client: OkHttpClient = network.client.newBuilder()
        .addNetworkInterceptor(rateLimitInterceptor)
        .addInterceptor(CoverInterceptor())
        .addInterceptor(MdAtHomeReportInterceptor(network.client, headersBuilder().build()))
        .build()

    private fun clientBuilder(): OkHttpClient = clientBuilder(getShowR18())

    private fun clientBuilder(r18Toggle: Int): OkHttpClient = network.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(rateLimitInterceptor)
        .addNetworkInterceptor { chain ->
            val originalCookies = chain.request().header("Cookie") ?: ""
            val newReq = chain
                .request()
                .newBuilder()
                .header("Cookie", "$originalCookies; ${cookiesHeader(r18Toggle)}")
                .build()
            chain.proceed(newReq)
        }.build()!!

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", "Tachiyomi " + System.getProperty("http.agent"))
    }

    private fun cookiesHeader(r18Toggle: Int): String {
        val cookies = mutableMapOf<String, String>()
        cookies["mangadex_h_toggle"] = r18Toggle.toString()
        return buildCookies(cookies)
    }

    private fun buildCookies(cookies: Map<String, String>) =
        cookies.entries.joinToString(separator = "; ", postfix = ";") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

    override fun popularMangaSelector() = "div.manga-entry"

    override fun latestUpdatesSelector() = "tr a.manga_title"

    // url matches default SortFilter selection (Rating Descending)
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/titles/7/$page/", headersBuilder().build(), CacheControl.FORCE_NETWORK)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/updates/$page", headersBuilder().build(), CacheControl.FORCE_NETWORK)
    }

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a.manga_title").first().let {
            val url = modifyMangaUrl(it.attr("href"))
            manga.setUrlWithoutDomain(url)
            manga.title = it.text().trim()
        }
        manga.thumbnail_url = formThumbUrl(manga.url)
        return manga
    }

    private fun modifyMangaUrl(url: String): String =
        url.replace("/title/", "/manga/").substringBeforeLast("/") + "/"

    private fun formThumbUrl(mangaUrl: String): String {
        var ext = ".jpg"

        if (getShowThumbnail() == LOW_QUALITY) {
            ext = ".thumb$ext"
        }

        return cdnUrl + "/images/manga/" + getMangaId(mangaUrl) + ext
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.let {
            manga.setUrlWithoutDomain(modifyMangaUrl(it.attr("href")))
            manga.title = it.text().trim()
        }
        manga.thumbnail_url = formThumbUrl(manga.url)

        return manga
    }

    override fun popularMangaNextPageSelector() =
        ".pagination li:not(.disabled) span[title*=last page]:not(disabled)"

    override fun latestUpdatesNextPageSelector() =
        ".pagination li:not(.disabled) span[title*=last page]:not(disabled)"

    override fun searchMangaNextPageSelector() =
        ".pagination li:not(.disabled) span[title*=last page]:not(disabled)"

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return clientBuilder().newCall(popularMangaRequest(page))
            .asObservableSuccess()
            .map { response ->
                popularMangaParse(response)
            }
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return clientBuilder().newCall(latestUpdatesRequest(page))
            .asObservableSuccess()
            .map { response ->
                latestUpdatesParse(response)
            }
    }

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList
    ): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_ID_SEARCH)) {
            val realQuery = query.removePrefix(PREFIX_ID_SEARCH)
            client.newCall(searchMangaByIdRequest(realQuery))
                .asObservableSuccess()
                .map { response ->
                    val details = mangaDetailsParse(response)
                    details.url = "/manga/$realQuery/"
                    MangasPage(listOf(details), false)
                }
        } else {
            getSearchClient(filters).newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map { response ->
                    searchMangaParse(response)
                }
        }
    }

    private fun getSearchClient(filters: FilterList): OkHttpClient {
        filters.forEach { filter ->
            when (filter) {
                is R18 -> {
                    return when (filter.state) {
                        1 -> clientBuilder(ALL)
                        2 -> clientBuilder(ONLY_R18)
                        3 -> clientBuilder(NO_R18)
                        else -> clientBuilder()
                    }
                }
            }
        }
        return clientBuilder()
    }

    private var groupSearch: String? = null

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page == 1) groupSearch = null
        val genresToInclude = mutableListOf<String>()
        val genresToExclude = mutableListOf<String>()

        // Do traditional search
        val url = HttpUrl.parse("$baseUrl/?page=search")!!.newBuilder()
            .addQueryParameter("p", page.toString())
            .addQueryParameter("title", query.replace(WHITESPACE_REGEX, " "))

        filters.forEach { filter ->
            when (filter) {
                is TextField -> url.addQueryParameter(filter.key, filter.state)
                is Demographic -> {
                    val demographicToInclude = mutableListOf<String>()
                    filter.state.forEach { content ->
                        if (content.isIncluded()) {
                            demographicToInclude.add(content.id)
                        }
                    }
                    if (demographicToInclude.isNotEmpty()) {
                        url.addQueryParameter("demos", demographicToInclude.joinToString(","))
                    }
                }
                is PublicationStatus -> {
                    val publicationToInclude = mutableListOf<String>()
                    filter.state.forEach { content ->
                        if (content.isIncluded()) {
                            publicationToInclude.add(content.id)
                        }
                    }
                    if (publicationToInclude.isNotEmpty()) {
                        url.addQueryParameter("statuses", publicationToInclude.joinToString(","))
                    }
                }
                is OriginalLanguage -> {
                    if (filter.state != 0) {
                        val number: String =
                            SOURCE_LANG_LIST.first { it.first == filter.values[filter.state] }.second
                        url.addQueryParameter("lang_id", number)
                    }
                }
                is TagInclusionMode -> {
                    url.addQueryParameter("tag_mode_inc", arrayOf("all", "any")[filter.state])
                }
                is TagExclusionMode -> {
                    url.addQueryParameter("tag_mode_exc", arrayOf("all", "any")[filter.state])
                }
                is ContentList -> {
                    filter.state.forEach { content ->
                        if (content.isExcluded()) {
                            genresToExclude.add(content.id)
                        } else if (content.isIncluded()) {
                            genresToInclude.add(content.id)
                        }
                    }
                }
                is FormatList -> {
                    filter.state.forEach { format ->
                        if (format.isExcluded()) {
                            genresToExclude.add(format.id)
                        } else if (format.isIncluded()) {
                            genresToInclude.add(format.id)
                        }
                    }
                }
                is GenreList -> {
                    filter.state.forEach { genre ->
                        if (genre.isExcluded()) {
                            genresToExclude.add(genre.id)
                        } else if (genre.isIncluded()) {
                            genresToInclude.add(genre.id)
                        }
                    }
                }
                is ThemeList -> {
                    filter.state.forEach { theme ->
                        if (theme.isExcluded()) {
                            genresToExclude.add(theme.id)
                        } else if (theme.isIncluded()) {
                            genresToInclude.add(theme.id)
                        }
                    }
                }
                is SortFilter -> {
                    if (filter.state != null) {
                        if (filter.state!!.ascending) {
                            url.addQueryParameter(
                                "s",
                                sortables[filter.state!!.index].second.toString()
                            )
                        } else {
                            url.addQueryParameter(
                                "s",
                                sortables[filter.state!!.index].third.toString()
                            )
                        }
                    }
                }
                is ScanGroup -> {
                    groupSearch = when {
                        filter.state.isNotEmpty() && page == 1 -> "$baseUrl/groups/0/1/${filter.state}"
                        filter.state.isNotEmpty() && page > 1 -> "${groupSearch!!}/$page"
                        else -> null
                    }
                }
            }
        }

        // Manually append genres list to avoid commas being encoded
        var urlToUse = url.toString()
        if (genresToInclude.isNotEmpty()) {
            urlToUse += "&tags_inc=" + genresToInclude.joinToString(",")
        }
        if (genresToExclude.isNotEmpty()) {
            urlToUse += "&tags_exc=" + genresToExclude.joinToString(",")
        }

        return GET(groupSearch ?: urlToUse, headersBuilder().build(), CacheControl.FORCE_NETWORK)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return if (response.request().url().toString().contains("/groups/")) {
            response.asJsoup()
                .select(".table > tbody:nth-child(2) > tr:nth-child(1) > td:nth-child(2) > a")
                .firstOrNull()?.attr("abs:href")
                ?.let {
                    groupSearch = "$it/manga/0"
                    super.searchMangaParse(
                        client.newCall(
                            GET(
                                groupSearch!!,
                                headersBuilder().build()
                            )
                        ).execute()
                    )
                }
                ?: MangasPage(emptyList(), false)
        } else {
            val document = response.asJsoup()
            if (document.select("#login_button")
                .isNotEmpty()
            ) throw Exception("Log in via WebView to enable search")

            val mangas = document.select(searchMangaSelector()).map { element ->
                searchMangaFromElement(element)
            }

            val hasNextPage = searchMangaNextPageSelector().let { selector ->
                document.select(selector).first()
            } != null

            MangasPage(mangas, hasNextPage)
        }
    }

    override fun searchMangaSelector() = "div.manga-entry"

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        element.select("a.manga_title").first().let {
            val url = modifyMangaUrl(it.attr("href"))
            manga.setUrlWithoutDomain(url)
            manga.title = it.text().trim()
        }

        manga.thumbnail_url = formThumbUrl(manga.url)

        return manga
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return clientBuilder().newCall(apiRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    private fun apiRequest(manga: SManga): Request {
        return GET(
            API_URL + API_MANGA + getMangaId(manga.url) + API_MANGA_INCLUDE_CHAPTERS,
            headers,
            CacheControl.FORCE_NETWORK
        )
    }

    private fun searchMangaByIdRequest(id: String): Request {
        return GET(API_URL + API_MANGA + id + API_MANGA_INCLUDE_CHAPTERS, headers, CacheControl.FORCE_NETWORK)
    }

    private fun getMangaId(url: String): String {
        val lastSection = url.trimEnd('/').substringAfterLast("/")
        return if (lastSection.toIntOrNull() != null) {
            lastSection
        } else {
            // this occurs if person has manga from before that had the id/name/
            url.trimEnd('/').substringBeforeLast("/").substringAfterLast("/")
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val manga = SManga.create()
        val jsonData = response.body()!!.string()
        val json = JsonParser().parse(jsonData).asJsonObject["data"]
        val mangaJson = json["manga"].asJsonObject
        val chapterJson = json["chapters"].asJsonArray
        manga.title = cleanString(mangaJson["title"].string)
        manga.thumbnail_url = mangaJson["mainCover"].string
        manga.description =
            cleanString(mangadexDescription.clean(mangaJson["description"].string))
        manga.author = cleanString(mangaJson["author"].array.map { it.string }.joinToString())
        manga.artist = cleanString(mangaJson["artist"].array.map { it.string }.joinToString())
        val status = mangaJson["publication"]["status"].int
        val finalChapterNumber = getFinalChapter(mangaJson)
        if ((status == 2 || status == 3) && chapterJson != null && isMangaCompleted(
                chapterJson,
                finalChapterNumber
            )
        ) {
            manga.status = SManga.COMPLETED
        } else if (status == 2 && chapterJson != null && isOneshot(
                chapterJson,
                finalChapterNumber
            )
        ) {
            manga.status = SManga.COMPLETED
        } else {
            manga.status = parseStatus(status)
        }

        val genres = if (mangaJson["isHentai"].bool) {
            listOf("Hentai")
        } else {
            listOf()
        } +
            mangaJson["tags"].array.mapNotNull { GENRES[it.string] } +
            mangaJson["publication"]["language"].string
        manga.genre = genres.joinToString(", ")
        return manga
    }

    // Remove bbcode tags as well as parses any html characters in description or chapter name to actual characters for example &hearts; will show ♥
    private fun cleanString(string: String): String {
        val bbRegex =
            """\[(\w+)[^]]*](.*?)\[/\1]""".toRegex()
        var intermediate = string
            .replace("[list]", "")
            .replace("[/list]", "")
            .replace("[*]", "")
        // Recursively remove nested bbcode
        while (bbRegex.containsMatchIn(intermediate)) {
            intermediate = intermediate.replace(bbRegex, "$2")
        }
        return Parser.unescapeEntities(intermediate, false)
    }

    override fun mangaDetailsParse(document: Document) = throw Exception("Not Used")

    override fun chapterListSelector() = ""

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return clientBuilder().newCall(apiRequest(manga))
            .asObservableSuccess()
            .map { response ->
                chapterListParse(response)
            }
    }

    private fun getFinalChapter(jsonObj: JsonObject): String =
        jsonObj.get("lastChapter").nullString?.trim() ?: ""

    private fun isOneshot(chapterJson: JsonArray, lastChapter: String): Boolean {
        val chapter =
            chapterJson.takeIf { it.size() > 0 }?.elementAt(0)?.asJsonObject?.get("title")?.string
        return if (chapter != null) {
            chapter == "Oneshot" || chapter.isEmpty() && lastChapter == "0"
        } else {
            false
        }
    }

    private fun isMangaCompleted(chapterJson: JsonArray, finalChapterNumber: String): Boolean {
        val count = chapterJson
            .filter { it.asJsonObject.get("language").string == internalLang }
            .filter { doesFinalChapterExist(finalChapterNumber, it) }.count()
        return count != 0
    }

    private fun doesFinalChapterExist(finalChapterNumber: String, chapterJson: JsonElement) =
        finalChapterNumber.isNotEmpty() && finalChapterNumber == chapterJson["chapter"].string.trim()

    override fun chapterListParse(response: Response): List<SChapter> {
        val now = Date().time
        val jsonData = response.body()!!.string()
        val json = JsonParser().parse(jsonData).asJsonObject["data"]
        val mangaJson = json["manga"].asJsonObject

        val status = mangaJson["publication"]["status"].int

        val finalChapterNumber = getFinalChapter(mangaJson)

        val chapterJson = json["chapters"].asJsonArray
        val chapters = mutableListOf<SChapter>()

        // Skip chapters that don't match the desired language, or are future releases
        val groups = json["groups"].array.map {
            val group = it.asJsonObject
            Pair(group["id"].int, group["name"].string)
        }.toMap()

        val hasMangaPlus = groups.containsKey(9097)

        chapterJson?.forEach { jsonElement ->
            val chapterElement = jsonElement.asJsonObject
            if (shouldKeepChapter(chapterElement, now)) {
                chapters.add(chapterFromJson(chapterElement, finalChapterNumber, status, groups))
            }
        }
        return chapters.also { if (it.isEmpty() && hasMangaPlus) throw Exception("This only has MangaPlus chapters, use the MangaPlus extension") }
    }

    /**
     * Filter out the following chapters:
     *   language doesn't match the chosen language
     *   Future chapters
     *   Chapters from MangaPlus since they have to be read in MangaPlus extension
     */
    private fun shouldKeepChapter(chapterJson: JsonObject, now: Long): Boolean {
        return when {
            chapterJson["language"].string != internalLang -> false
            (chapterJson["timestamp"].asLong * 1000) > now -> false
            chapterJson["groups"].array.map { it.string }.contains("9097") -> false
            else -> true
        }
    }

    private fun chapterFromJson(
        chapterJson: JsonObject,
        finalChapterNumber: String,
        status: Int,
        groups: Map<Int, String>
    ): SChapter {
        val chapter = SChapter.create()
        chapter.url = OLD_API_CHAPTER + chapterJson["id"].string
        val chapterName = mutableListOf<String>()
        // Build chapter name
        if (chapterJson["volume"].string.isNotBlank()) {
            chapterName.add("Vol." + chapterJson.get("volume").string)
        }
        if (chapterJson["chapter"].string.isNotBlank()) {
            chapterName.add("Ch." + chapterJson.get("chapter").string)
        }
        if (chapterJson["title"].string.isNotBlank()) {
            if (chapterName.isNotEmpty()) {
                chapterName.add("-")
            }
            chapterName.add(chapterJson["title"].string)
        }
        // if volume, chapter and title is empty its a oneshot
        if (chapterName.isEmpty()) {
            chapterName.add("Oneshot")
        }
        if ((status == 2 || status == 3) && doesFinalChapterExist(
                finalChapterNumber,
                chapterJson
            )
        ) {
            chapterName.add("[END]")
        }

        chapter.name = cleanString(chapterName.joinToString(" "))
        // Convert from unix time
        chapter.date_upload = chapterJson.get("timestamp").long * 1000
        val scanlatorName = chapterJson["groups"].asJsonArray.map { it.int }.map {
            groups[it]
        }

        chapter.scanlator = cleanString(scanlatorName.joinToString(" & "))

        return chapter
    }

    override fun chapterFromElement(element: Element) = throw Exception("Not used")

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return client.newCall(pageListRequest(chapter))
            .asObservable().doOnNext { response ->
                if (!response.isSuccessful) {
                    response.close()
                    if (response.code() == 451) {
                        error("Error 451: Log in to view manga; contact MangaDex if error persists.")
                    } else {
                        throw Exception("HTTP error ${response.code()}")
                    }
                }
            }
            .map { response ->
                pageListParse(response)
            }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        if (chapter.scanlator == "MangaPlus") {
            throw Exception("Chapter is licensed; use the MangaPlus extension")
        }

        val server = getServer()
        val saver = getUseDataSaver()
        val newUrl = API_URL + chapter.url.replace(OLD_API_CHAPTER, NEW_API_CHAPTER)
        return GET(
            "$newUrl?server=$server&saver=$saver",
            headers,
            CacheControl.FORCE_NETWORK
        )
    }

    override fun pageListParse(document: Document) = throw Exception("Not used")

    override fun pageListParse(response: Response): List<Page> {
        val jsonData = response.body()!!.string()
        val json = JsonParser().parse(jsonData).asJsonObject["data"]

        val hash = json["hash"].string
        val server = json["server"].string

        return json["pages"].asJsonArray.mapIndexed { idx, it ->
            val url = "$hash/${it.asString}"
            val mdAtHomeMetadataUrl = "$server,${response.request().url()},${Date().time}"
            Page(idx, mdAtHomeMetadataUrl, url)
        }
    }

    override fun imageRequest(page: Page): Request {
        val url = when {
            // Legacy
            page.url.isEmpty() -> page.imageUrl!!
            // Some images are hosted elsewhere
            !page.url.startsWith("http") -> baseUrl + page.url.substringBefore(",") + page.imageUrl
            // New chapters on MD servers
            page.url.contains("https://mangadex.org/data") -> page.url.substringBefore(",") + page.imageUrl
            // MD@Home token handling
            else -> {
                val tokenLifespan = 5 * 60 * 1000
                val data = page.url.split(",")
                var tokenedServer = data[0]
                if (Date().time - data[2].toLong() > tokenLifespan) {
                    val tokenRequestUrl = data[1]
                    val cacheControl =
                        if (Date().time - (tokenTracker[tokenRequestUrl] ?: 0) > tokenLifespan) {
                            tokenTracker[tokenRequestUrl] = Date().time
                            CacheControl.FORCE_NETWORK
                        } else {
                            CacheControl.FORCE_CACHE
                        }
                    val jsonData =
                        client.newCall(GET(tokenRequestUrl, headers, cacheControl)).execute()
                            .body()!!.string()
                    tokenedServer =
                        JsonParser().parse(jsonData).asJsonObject["data"]["server"].string
                }
                tokenedServer + page.imageUrl
            }
        }

        return GET(url, headers)
    }

    // chapter url where we get the token, last request time
    private val tokenTracker = hashMapOf<String, Long>()

    override fun imageUrlParse(document: Document): String = ""

    private fun parseStatus(status: Int) = when (status) {
        1 -> SManga.ONGOING
        else -> SManga.UNKNOWN
    }

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val r18Pref = androidx.preference.ListPreference(screen.context).apply {
            key = SHOW_R18_PREF_Title
            title = SHOW_R18_PREF_Title

            title = SHOW_R18_PREF_Title
            entries = arrayOf("Show No R18+", "Show All", "Show Only R18+")
            entryValues = arrayOf("0", "1", "2")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                preferences.edit().putInt(SHOW_R18_PREF, index).commit()
            }
        }
        val thumbsPref = androidx.preference.ListPreference(screen.context).apply {
            key = SHOW_THUMBNAIL_PREF_Title
            title = SHOW_THUMBNAIL_PREF_Title
            entries = arrayOf("Show high quality", "Show low quality")
            entryValues = arrayOf("0", "1")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                preferences.edit().putInt(SHOW_THUMBNAIL_PREF, index).commit()
            }
        }
        val serverPref = androidx.preference.ListPreference(screen.context).apply {
            key = SERVER_PREF_Title
            title = SERVER_PREF_Title
            entries = SERVER_PREF_ENTRIES
            entryValues = SERVER_PREF_ENTRY_VALUES
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(SERVER_PREF, entry).commit()
            }
        }
        val dataSaverPref = androidx.preference.ListPreference(screen.context).apply {
            key = DATA_SAVER_PREF_Title
            title = DATA_SAVER_PREF_Title
            entries = arrayOf("Disable", "Enable")
            entryValues = arrayOf("0", "1")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                preferences.edit().putInt(DATA_SAVER_PREF, index).commit()
            }
        }

        screen.addPreference(r18Pref)
        screen.addPreference(thumbsPref)
        screen.addPreference(serverPref)
        screen.addPreference(dataSaverPref)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val r18Pref = ListPreference(screen.context).apply {
            key = SHOW_R18_PREF_Title
            title = SHOW_R18_PREF_Title

            title = SHOW_R18_PREF_Title
            entries = arrayOf("Show No R18+", "Show All", "Show Only R18+")
            entryValues = arrayOf("0", "1", "2")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                preferences.edit().putInt(SHOW_R18_PREF, index).commit()
            }
        }
        val thumbsPref = ListPreference(screen.context).apply {
            key = SHOW_THUMBNAIL_PREF_Title
            title = SHOW_THUMBNAIL_PREF_Title
            entries = arrayOf("Show high quality", "Show low quality")
            entryValues = arrayOf("0", "1")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                preferences.edit().putInt(SHOW_THUMBNAIL_PREF, index).commit()
            }
        }
        val serverPref = ListPreference(screen.context).apply {
            key = SERVER_PREF_Title
            title = SERVER_PREF_Title
            entries = SERVER_PREF_ENTRIES
            entryValues = SERVER_PREF_ENTRY_VALUES
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(SERVER_PREF, entry).commit()
            }
        }
        val dataSaverPref = ListPreference(screen.context).apply {
            key = DATA_SAVER_PREF_Title
            title = DATA_SAVER_PREF_Title
            entries = arrayOf("Disable", "Enable")
            entryValues = arrayOf("0", "1")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                preferences.edit().putInt(DATA_SAVER_PREF, index).commit()
            }
        }

        screen.addPreference(r18Pref)
        screen.addPreference(thumbsPref)
        screen.addPreference(serverPref)
        screen.addPreference(dataSaverPref)
    }

    private fun getShowR18(): Int = preferences.getInt(SHOW_R18_PREF, 0)
    private fun getShowThumbnail(): Int = preferences.getInt(SHOW_THUMBNAIL_PREF, 0)
    private fun getServer(): String {
        val default = SERVER_PREF_ENTRY_VALUES.first()
        return preferences.getString(SERVER_PREF, default).takeIf { it in SERVER_PREF_ENTRY_VALUES }
            ?: default
    }

    private fun getUseDataSaver(): Int = preferences.getInt(DATA_SAVER_PREF, 0)

    private class TextField(name: String, val key: String) : Filter.Text(name)
    private class Tag(val id: String, name: String) : Filter.TriState(name)
    private class Demographic(demographics: List<Tag>) :
        Filter.Group<Tag>("Demographic", demographics)

    private class PublicationStatus(publications: List<Tag>) :
        Filter.Group<Tag>("Publication", publications)

    private class ContentList(contents: List<Tag>) : Filter.Group<Tag>("Content", contents)
    private class FormatList(formats: List<Tag>) : Filter.Group<Tag>("Format", formats)
    private class GenreList(genres: List<Tag>) : Filter.Group<Tag>("Genres", genres)
    private class R18 :
        Filter.Select<String>("R18+", arrayOf("Default", "Show all", "Show only", "Show none"))

    private class ScanGroup(name: String) : Filter.Text(name)

    private fun getDemographic() = listOf(
        Tag("1", "Shounen"),
        Tag("2", "Shoujo"),
        Tag("3", "Seinen"),
        Tag("4", "Josei")
    ).sortedWith(compareBy { it.name })

    private fun getPublicationStatus() = listOf(
        Tag("1", "Ongoing"),
        Tag("2", "Completed"),
        Tag("3", "Cancelled"),
        Tag("4", "Hiatus")
    ).sortedWith(compareBy { it.name })

    private class ThemeList(themes: List<Tag>) : Filter.Group<Tag>("Themes", themes)
    private class TagInclusionMode :
        Filter.Select<String>("Tag inclusion mode", arrayOf("All (and)", "Any (or)"), 0)

    private class TagExclusionMode :
        Filter.Select<String>("Tag exclusion mode", arrayOf("All (and)", "Any (or)"), 1)

    // default selection (Rating Descending) matches popularMangaRequest url
    class SortFilter : Filter.Sort(
        "Sort",
        sortables.map { it.first }.toTypedArray(),
        Selection(3, false)
    )

    private class OriginalLanguage :
        Filter.Select<String>("Original Language", SOURCE_LANG_LIST.map { it.first }.toTypedArray())

    override fun getFilterList() = FilterList(
        TextField("Author", "author"),
        TextField("Artist", "artist"),
        R18(),
        SortFilter(),
        Demographic(getDemographic()),
        PublicationStatus(getPublicationStatus()),
        OriginalLanguage(),
        ContentList(getContentList()),
        FormatList(getFormatList()),
        GenreList(getGenreList()),
        ThemeList(getThemeList()),
        TagInclusionMode(),
        TagExclusionMode(),
        Filter.Separator(),
        Filter.Header("Group search ignores other inputs"),
        ScanGroup("Search for manga by scanlator group")
    )

    private fun getContentList() = listOf(
        Tag("9", "Ecchi"),
        Tag("32", "Smut"),
        Tag("49", "Gore"),
        Tag("50", "Sexual Violence")
    ).sortedWith(compareBy { it.name })

    private fun getFormatList() = listOf(
        Tag("1", "4-koma"),
        Tag("4", "Award Winning"),
        Tag("7", "Doujinshi"),
        Tag("21", "Oneshot"),
        Tag("36", "Long Strip"),
        Tag("42", "Adaptation"),
        Tag("43", "Anthology"),
        Tag("44", "Web Comic"),
        Tag("45", "Full Color"),
        Tag("46", "User Created"),
        Tag("47", "Official Colored"),
        Tag("48", "Fan Colored")
    ).sortedWith(compareBy { it.name })

    private fun getGenreList() = listOf(
        Tag("2", "Action"),
        Tag("3", "Adventure"),
        Tag("5", "Comedy"),
        Tag("8", "Drama"),
        Tag("10", "Fantasy"),
        Tag("13", "Historical"),
        Tag("14", "Horror"),
        Tag("17", "Mecha"),
        Tag("18", "Medical"),
        Tag("20", "Mystery"),
        Tag("22", "Psychological"),
        Tag("23", "Romance"),
        Tag("25", "Sci-Fi"),
        Tag("28", "Shoujo Ai"),
        Tag("30", "Shounen Ai"),
        Tag("31", "Slice of Life"),
        Tag("33", "Sports"),
        Tag("35", "Tragedy"),
        Tag("37", "Yaoi"),
        Tag("38", "Yuri"),
        Tag("41", "Isekai"),
        Tag("51", "Crime"),
        Tag("52", "Magical Girls"),
        Tag("53", "Philosophical"),
        Tag("54", "Superhero"),
        Tag("55", "Thriller"),
        Tag("56", "Wuxia")
    ).sortedWith(compareBy { it.name })

    private fun getThemeList() = listOf(
        Tag("6", "Cooking"),
        Tag("11", "Gyaru"),
        Tag("12", "Harem"),
        Tag("16", "Martial Arts"),
        Tag("19", "Music"),
        Tag("24", "School Life"),
        Tag("34", "Supernatural"),
        Tag("40", "Video Games"),
        Tag("57", "Aliens"),
        Tag("58", "Animals"),
        Tag("59", "Crossdressing"),
        Tag("60", "Demons"),
        Tag("61", "Delinquents"),
        Tag("62", "Genderswap"),
        Tag("63", "Ghosts"),
        Tag("64", "Monster Girls"),
        Tag("65", "Loli"),
        Tag("66", "Magic"),
        Tag("67", "Military"),
        Tag("68", "Monsters"),
        Tag("69", "Ninja"),
        Tag("70", "Office Workers"),
        Tag("71", "Police"),
        Tag("72", "Post-Apocalyptic"),
        Tag("73", "Reincarnation"),
        Tag("74", "Reverse Harem"),
        Tag("75", "Samurai"),
        Tag("76", "Shota"),
        Tag("77", "Survival"),
        Tag("78", "Time Travel"),
        Tag("79", "Vampires"),
        Tag("80", "Traditional Games"),
        Tag("81", "Virtual Reality"),
        Tag("82", "Zombies"),
        Tag("83", "Incest"),
        Tag("84", "Mafia"),
        Tag("85", "Villainess")
    ).sortedWith(compareBy { it.name })

    private val GENRES =
        (getContentList() + getFormatList() + getGenreList() + getThemeList()).map { it.id to it.name }
            .toMap()

    companion object {
        private val WHITESPACE_REGEX = "\\s".toRegex()

        // This number matches to the cookie
        private const val NO_R18 = 0
        private const val ALL = 1
        private const val ONLY_R18 = 2

        private const val SHOW_R18_PREF_Title = "Default R18 Setting"
        private const val SHOW_R18_PREF = "showR18Default"

        private const val LOW_QUALITY = 1

        private const val SHOW_THUMBNAIL_PREF_Title = "Default thumbnail quality"
        private const val SHOW_THUMBNAIL_PREF = "showThumbnailDefault"

        private const val SERVER_PREF_Title = "Image server"
        private const val SERVER_PREF = "imageServer"
        private val SERVER_PREF_ENTRIES =
            arrayOf("Automatic", "NA/EU 1", "NA/EU 2", "Rest of the world")
        private val SERVER_PREF_ENTRY_VALUES = arrayOf("0", "na", "na2", "row")

        private const val DATA_SAVER_PREF_Title = "Data saver"
        private const val DATA_SAVER_PREF = "dataSaver"

        private const val API_URL = "https://api.mangadex.org"
        private const val API_MANGA = "/v2/manga/"
        private const val API_MANGA_INCLUDE_CHAPTERS = "?include=chapters"
        private const val OLD_API_CHAPTER = "/api/chapter/"
        private const val NEW_API_CHAPTER = "/v2/chapter/"

        const val PREFIX_ID_SEARCH = "id:"

        private val sortables = listOf(
            Triple("Update date", 0, 1),
            Triple("Alphabetically", 2, 3),
            Triple("Number of comments", 4, 5),
            Triple("Rating", 6, 7),
            Triple("Views", 8, 9),
            Triple("Follows", 10, 11)
        )

        private val SOURCE_LANG_LIST = listOf(
            Pair("All", "0"),
            Pair("Japanese", "2"),
            Pair("English", "1"),
            Pair("Polish", "3"),
            Pair("German", "8"),
            Pair("French", "10"),
            Pair("Vietnamese", "12"),
            Pair("Chinese", "21"),
            Pair("Indonesian", "27"),
            Pair("Korean", "28"),
            Pair("Spanish (LATAM)", "29"),
            Pair("Thai", "32"),
            Pair("Filipino", "34")
        )
    }
}

class CoverInterceptor : Interceptor {
    private val coverRegex = Regex("""/images/.*\.jpg""")

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        return chain.proceed(chain.request()).let { response ->
            if (response.code() == 404 && originalRequest.url().toString().contains(coverRegex)) {
                response.close()
                chain.proceed(
                    originalRequest.newBuilder().url(
                        originalRequest.url().toString().substringBeforeLast(".") + ".thumb.jpg"
                    ).build()
                )
            } else {
                response
            }
        }
    }
}

class MdAtHomeReportInterceptor(
    private val client: OkHttpClient,
    private val headers: Headers
) : Interceptor {

    private val gson: Gson by lazy { Gson() }
    private val mdAtHomeUrlRegex = Regex("""^https://[\w\d]+\.[\w\d]+\.mangadex\.network.*${'$'}""")

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        return chain.proceed(chain.request()).let { response ->
            val url = originalRequest.url().toString()
            if (url.contains(mdAtHomeUrlRegex)) {
                val jsonString = gson.toJson(
                    mapOf(
                        "url" to url,
                        "success" to response.isSuccessful,
                        "bytes" to response.peekBody(Long.MAX_VALUE).bytes().size
                    )
                )

                val postResult = client.newCall(
                    POST(
                        "https://api.mangadex.network/report",
                        headers,
                        RequestBody.create(null, jsonString)
                    )
                )
                try {
                    postResult.execute()
                } catch (e: Exception) {
                    Log.e("MangaDex", "Error trying to POST report to MD@Home: ${e.message}")
                }
            }

            response
        }
    }
}
