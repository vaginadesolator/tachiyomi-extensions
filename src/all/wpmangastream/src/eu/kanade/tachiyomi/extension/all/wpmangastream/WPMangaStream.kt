package eu.kanade.tachiyomi.extension.all.wpmangastream

import android.app.Application
import android.content.SharedPreferences
import android.support.v7.preference.ListPreference
import android.support.v7.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

abstract class WPMangaStream(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
) : ConfigurableSource, ParsedHttpSource() {
    override val supportsLatest = true

    companion object {
        private const val MID_QUALITY = 1
        private const val LOW_QUALITY = 2

        private const val SHOW_THUMBNAIL_PREF_Title = "Default thumbnail quality"
        private const val SHOW_THUMBNAIL_PREF = "showThumbnailDefault"
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val thumbsPref = androidx.preference.ListPreference(screen.context).apply {
            key = SHOW_THUMBNAIL_PREF_Title
            title = SHOW_THUMBNAIL_PREF_Title
            entries = arrayOf("Show high quality", "Show mid quality", "Show low quality")
            entryValues = arrayOf("0", "1", "2")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                preferences.edit().putInt(SHOW_THUMBNAIL_PREF, index).commit()
            }
        }
        screen.addPreference(thumbsPref)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val thumbsPref = ListPreference(screen.context).apply {
            key = SHOW_THUMBNAIL_PREF_Title
            title = SHOW_THUMBNAIL_PREF_Title
            entries = arrayOf("Show high quality", "Show mid quality", "Show low quality")
            entryValues = arrayOf("0", "1", "2")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                preferences.edit().putInt(SHOW_THUMBNAIL_PREF, index).commit()
            }
        }
        screen.addPreference(thumbsPref)
    }

    private fun getShowThumbnail(): Int = preferences.getInt(SHOW_THUMBNAIL_PREF, 0)

    private val rateLimitInterceptor = RateLimitInterceptor(4)

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(rateLimitInterceptor)
        .build()

    protected fun Element.imgAttr(): String = if (this.hasAttr("data-src")) this.attr("abs:data-src") else this.attr("abs:src")
    protected fun Elements.imgAttr(): String = this.first().imgAttr()

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/?page=$page&order=popular", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/?page=$page&order=update", headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = HttpUrl.parse("$baseUrl/manga/")!!.newBuilder()
        url.addQueryParameter("title", query)
        url.addQueryParameter("page", page.toString())
        filters.forEach { filter ->
            when (filter) {
                is AuthorFilter -> {
                    url.addQueryParameter("author", filter.state)
                }
                is YearFilter -> {
                    url.addQueryParameter("yearx", filter.state)
                }
                is StatusFilter -> {
                    val status = when (filter.state) {
                        Filter.TriState.STATE_INCLUDE -> "completed"
                        Filter.TriState.STATE_EXCLUDE -> "ongoing"
                        else -> ""
                    }
                    url.addQueryParameter("status", status)
                }
                is TypeFilter -> {
                    url.addQueryParameter("type", filter.toUriPart())
                }
                is SortByFilter -> {
                    url.addQueryParameter("order", filter.toUriPart())
                }
                is GenreListFilter -> {
                    filter.state
                        .filter { it.state != Filter.TriState.STATE_IGNORE }
                        .forEach { url.addQueryParameter("genre[]", it.id) }
                }
            }
        }
        return GET(url.build().toString(), headers)
    }

    override fun popularMangaSelector() = "div.bs"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url = element.select("div.limit img").imgAttr()
        element.select("div.bsx > a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.attr("title")
        }
        return manga
    }

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun popularMangaNextPageSelector(): String? = "a.next.page-numbers, a.r"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            document.select("div.bigcontent, div.animefull, div.main-info").firstOrNull()?.let { infoElement ->
                genre = infoElement.select("span:contains(Genre) a, .mgen a").joinToString { it.text() }
                status = parseStatus(infoElement.select("span:contains(Status:), .imptdt:contains(Status) i").firstOrNull()?.ownText())
                author = infoElement.select("span:contains(Author:), span:contains(Pengarang:), .fmed b:contains(Author)+span, .imptdt:contains(Author) i").firstOrNull()?.ownText()
                artist = infoElement.select(".fmed b:contains(Artist)+span, .imptdt:contains(Artist) i").firstOrNull()?.ownText()
                description = infoElement.select("div.desc p, div.entry-content p").joinToString("\n") { it.text() }
                thumbnail_url = infoElement.select("div.thumb img").imgAttr()
            }
        }
    }

    protected fun parseStatus(element: String?): Int = when {
        element == null -> SManga.UNKNOWN
        listOf("ongoing", "publishing").any { it.contains(element, ignoreCase = true) } -> SManga.ONGOING
        listOf("completed").any { it.contains(element, ignoreCase = true) } -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "div.bxcl ul li, div.cl ul li, li:has(div.chbox):has(div.eph-num)"

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select(chapterListSelector()).map { chapterFromElement(it) }

        // Add timestamp to latest chapter, taken from "Updated On". so source which not provide chapter timestamp will have atleast one
        val date = document.select(".fmed:contains(update) time ,span:contains(update) time").attr("datetime")
        val checkChapter = document.select(chapterListSelector()).firstOrNull()
        if (date != "" && checkChapter != null) chapters[0].date_upload = parseDate(date)

        return chapters
    }

    private fun parseDate(date: String): Long {
        return SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(date)?.time ?: 0L
    }

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select(".lchx > a, span.leftoff a, div.eph-num > a").first()
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = if (urlElement.select("span.chapternum").isNotEmpty()) urlElement.select("span.chapternum").text() else urlElement.text()
        chapter.date_upload = element.select("span.rightoff, time, span.chapterdate").firstOrNull()?.text()?.let { parseChapterDate(it) } ?: 0
        return chapter
    }

    fun parseChapterDate(date: String): Long {
        return if (date.contains("ago")) {
            val value = date.split(' ')[0].toInt()
            when {
                "min" in date -> Calendar.getInstance().apply {
                    add(Calendar.MINUTE, value * -1)
                }.timeInMillis
                "hour" in date -> Calendar.getInstance().apply {
                    add(Calendar.HOUR_OF_DAY, value * -1)
                }.timeInMillis
                "day" in date -> Calendar.getInstance().apply {
                    add(Calendar.DATE, value * -1)
                }.timeInMillis
                "week" in date -> Calendar.getInstance().apply {
                    add(Calendar.DATE, value * 7 * -1)
                }.timeInMillis
                "month" in date -> Calendar.getInstance().apply {
                    add(Calendar.MONTH, value * -1)
                }.timeInMillis
                "year" in date -> Calendar.getInstance().apply {
                    add(Calendar.YEAR, value * -1)
                }.timeInMillis
                else -> {
                    0L
                }
            }
        } else {
            try {
                dateFormat.parse(date)?.time ?: 0
            } catch (_: Exception) {
                0L
            }
        }
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        val basic = Regex("""Chapter\s([0-9]+)""")
        when {
            basic.containsMatchIn(chapter.name) -> {
                basic.find(chapter.name)?.let {
                    chapter.chapter_number = it.groups[1]?.value!!.toFloat()
                }
            }
        }
    }

    open val pageSelector = "div#readerarea img"

    override fun pageListParse(document: Document): List<Page> {
        var pages = mutableListOf<Page>()
        document.select(pageSelector)
            .filterNot { it.attr("src").isNullOrEmpty() }
            .mapIndexed { i, img -> pages.add(Page(i, "", img.attr("abs:src"))) }

        // Some wpmangastream sites now load pages via javascript
        if (pages.isNotEmpty()) { return pages }

        val docString = document.toString()
        val imageListRegex = Regex("\\\"images.*?:.*?(\\[.*?\\])")

        val imageList = JSONArray(imageListRegex.find(docString)!!.destructured.toList()[0])

        for (i in 0 until imageList.length()) {
            pages.add(Page(i, "", imageList.getString(i)))
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    override fun imageRequest(page: Page): Request {
        val headers = Headers.Builder()
        headers.apply {
            add("Referer", baseUrl)
            add("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us; LGMS323 Build/KOT49I.MS32310c) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/76.0.3809.100 Mobile Safari/537.36")
        }

        if (page.imageUrl!!.contains(".wp.com")) {
            headers.apply {
                add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3")
            }
        }

        return GET(getImageUrl(page.imageUrl!!, getShowThumbnail()), headers.build())
    }

    private fun getImageUrl(originalUrl: String, quality: Int): String {
        val url = originalUrl.substringAfter("//")
        return when (quality) {
            LOW_QUALITY -> "https://images.weserv.nl/?w=300&q=70&url=$url"
            MID_QUALITY -> "https://images.weserv.nl/?w=600&q=70&url=$url"
            else -> originalUrl
        }
    }

    private class AuthorFilter : Filter.Text("Author")

    private class YearFilter : Filter.Text("Year")

    protected class TypeFilter : UriPartFilter(
        "Type",
        arrayOf(
            Pair("Default", ""),
            Pair("Manga", "Manga"),
            Pair("Manhwa", "Manhwa"),
            Pair("Manhua", "Manhua"),
            Pair("Comic", "Comic")
        )
    )

    protected class SortByFilter : UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Default", ""),
            Pair("A-Z", "title"),
            Pair("Z-A", "titlereverse"),
            Pair("Latest Update", "update"),
            Pair("Latest Added", "latest"),
            Pair("Popular", "popular")
        )
    )

    protected class StatusFilter : UriPartFilter(
        "Status",
        arrayOf(
            Pair("All", ""),
            Pair("Ongoing", "ongoing"),
            Pair("Completed", "completed")
        )
    )

    protected class Genre(name: String, val id: String = name) : Filter.TriState(name)
    protected class GenreListFilter(genres: List<Genre>) : Filter.Group<Genre>("Genre", genres)

    override fun getFilterList() = FilterList(
        Filter.Header("NOTE: Ignored if using text search!"),
        Filter.Header("Genre exclusion not available for all sources"),
        Filter.Separator(),
        AuthorFilter(),
        YearFilter(),
        StatusFilter(),
        TypeFilter(),
        SortByFilter(),
        GenreListFilter(getGenreList())
    )

    protected open fun getGenreList(): List<Genre> = listOf(
        Genre("4 Koma", "4-koma"),
        Genre("Action", "action"),
        Genre("Adult", "adult"),
        Genre("Adventure", "adventure"),
        Genre("Comedy", "comedy"),
        Genre("Completed", "completed"),
        Genre("Cooking", "cooking"),
        Genre("Crime", "crime"),
        Genre("Demon", "demon"),
        Genre("Demons", "demons"),
        Genre("Doujinshi", "doujinshi"),
        Genre("Drama", "drama"),
        Genre("Ecchi", "ecchi"),
        Genre("Fantasy", "fantasy"),
        Genre("Game", "game"),
        Genre("Games", "games"),
        Genre("Gender Bender", "gender-bender"),
        Genre("Gore", "gore"),
        Genre("Harem", "harem"),
        Genre("Historical", "historical"),
        Genre("Horror", "horror"),
        Genre("Isekai", "isekai"),
        Genre("Josei", "josei"),
        Genre("Magic", "magic"),
        Genre("Manga", "manga"),
        Genre("Manhua", "manhua"),
        Genre("Manhwa", "manhwa"),
        Genre("Martial Art", "martial-art"),
        Genre("Martial Arts", "martial-arts"),
        Genre("Mature", "mature"),
        Genre("Mecha", "mecha"),
        Genre("Military", "military"),
        Genre("Monster", "monster"),
        Genre("Monster Girls", "monster-girls"),
        Genre("Monsters", "monsters"),
        Genre("Music", "music"),
        Genre("Mystery", "mystery"),
        Genre("One-shot", "one-shot"),
        Genre("Oneshot", "oneshot"),
        Genre("Police", "police"),
        Genre("Pshycological", "pshycological"),
        Genre("Psychological", "psychological"),
        Genre("Reincarnation", "reincarnation"),
        Genre("Reverse Harem", "reverse-harem"),
        Genre("Romancce", "romancce"),
        Genre("Romance", "romance"),
        Genre("Samurai", "samurai"),
        Genre("School", "school"),
        Genre("School Life", "school-life"),
        Genre("Sci-fi", "sci-fi"),
        Genre("Seinen", "seinen"),
        Genre("Shoujo", "shoujo"),
        Genre("Shoujo Ai", "shoujo-ai"),
        Genre("Shounen", "shounen"),
        Genre("Shounen Ai", "shounen-ai"),
        Genre("Slice of Life", "slice-of-life"),
        Genre("Sports", "sports"),
        Genre("Super Power", "super-power"),
        Genre("Supernatural", "supernatural"),
        Genre("Thriller", "thriller"),
        Genre("Time Travel", "time-travel"),
        Genre("Tragedy", "tragedy"),
        Genre("Vampire", "vampire"),
        Genre("Webtoon", "webtoon"),
        Genre("Webtoons", "webtoons"),
        Genre("Yaoi", "yaoi"),
        Genre("Yuri", "yuri"),
        Genre("Zombies", "zombies")
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
