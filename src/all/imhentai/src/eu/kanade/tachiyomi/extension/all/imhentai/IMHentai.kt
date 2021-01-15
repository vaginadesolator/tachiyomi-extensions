package eu.kanade.tachiyomi.extension.all.imhentai

import eu.kanade.tachiyomi.annotations.Nsfw
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import rx.Observable

@Nsfw
class IMHentai : ParsedHttpSource() {

    private val pageLoadUrl: String = "https://imhentai.com/inc/thumbs_loader.php"

    private val pageLoadHeaders: Headers = Headers.Builder().apply {
        add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        add("X-Requested-With", "XMLHttpRequest")
    }.build()

    override val baseUrl: String = "https://imhentai.com"
    override val lang = "en"
    override val name: String = "IMHentai"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    // Popular

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            thumbnail_url = element.select(".inner_thumb img").attr("src")
            with(element.select(".caption a")) {
                url = this.attr("href")
                title = this.text()
            }
        }
    }

    override fun popularMangaNextPageSelector(): String = ".pagination li a:contains(Next):not([tabindex])"

    override fun popularMangaSelector(): String = ".thumbs_container .thumb"

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular?page=$page")

    // Latest

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/search/?lt=1&page=$page")

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    // Search

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    private fun toBinary(boolean: Boolean) = if (boolean) "1" else "0"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = HttpUrl.parse("$baseUrl/search")!!.newBuilder()
            .addQueryParameter("key", query)
            .addQueryParameter("page", page.toString())

        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is LanguageFilters -> {
                    filter.state.forEach {
                        url.addQueryParameter(it.uri, toBinary(it.state))
                    }
                }
                is CategoryFilters -> {
                    filter.state.forEach {
                        url.addQueryParameter(it.uri, toBinary(it.state))
                    }
                }
                is SortOrderFilter -> {
                    getSortOrderURIs().forEachIndexed { index, pair ->
                        url.addQueryParameter(pair.second, toBinary(filter.state == index))
                    }
                }
            }
        }

        return GET(url.toString())
    }

    override fun searchMangaSelector(): String = popularMangaSelector()

    // Details

    private fun Elements.csvText(splitTagSeparator: String = ", "): String {
        return this.joinToString {
            listOf(
                it.ownText(),
                it.select(".split_tag")?.text()
                    ?.trim()
                    ?.removePrefix("| ")
            )
                .filter { s -> !s.isNullOrBlank() }
                .joinToString(splitTagSeparator)
        }
    }

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val mangaInfoElement = document.select(".galleries_info")
        val infoMap = mangaInfoElement.select("li:not(.pages)").map {
            it.select("span.tags_text").text().removeSuffix(":") to it.select(".tag")
        }.toMap()

        artist = infoMap["Artists"]?.csvText(" | ")

        author = artist

        genre = infoMap["Tags"]?.csvText()

        status = SManga.COMPLETED

        val altTitle = document.select(".subtitle").text().ifBlank { null }

        description = listOf(
            "Parodies",
            "Characters",
            "Groups",
            "Languages",
            "Category"
        ).map { it to infoMap[it]?.csvText() }
            .let { listOf(Pair("Alternate Title", altTitle)) + it }
            .filter { !it.second.isNullOrEmpty() }
            .joinToString("\n\n") { "${it.first}:\n${it.second}" }
    }

    // Chapters

    private fun pageLoadMetaParse(document: Document): String {
        return document.select(".gallery_divider ~ input[type=\"hidden\"]").map { m ->
            m.attr("id") to m.attr("value")
        }.toMap().let {
            listOf(
                Pair("server", "load_server"),
                Pair("u_id", "gallery_id"),
                Pair("g_id", "load_id"),
                Pair("img_dir", "load_dir"),
                Pair("total_pages", "load_pages")
            ).map { meta -> "${meta.first}=${it[meta.second]}" }
                .let { payload -> payload + listOf("type=2", "visible_pages=0") }
                .joinToString("&")
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        return listOf(
            SChapter.create().apply {
                setUrlWithoutDomain(response.request().url().toString())
                name = "Chapter"
                chapter_number = 1f
                page_count = response.asJsoup().select("li.pages").text()
                    .substringAfter("Pages: ").toInt()
            }
        )
    }

    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException("Not used")

    override fun chapterListSelector(): String = throw UnsupportedOperationException("Not used")

    // Pages

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return client.newCall(GET("$baseUrl${chapter.url}"))
            .asObservableSuccess()
            .map { pageLoadMetaParse(it.asJsoup()) }
            .map { RequestBody.create(MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"), it) }
            .concatMap { client.newCall(POST(pageLoadUrl, pageLoadHeaders, it)).asObservableSuccess() }
            .map { pageListParse(it) }
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("a").mapIndexed { i, element ->
            Page(i, element.attr("href"), element.select(".lazy.preloader[src]").attr("src").replace("t.", "."))
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    // Filters

    private class SortOrderFilter(sortOrderURIs: List<Pair<String, String>>) : Filter.Select<String>("Sort By", sortOrderURIs.map { it.first }.toTypedArray())
    private class SearchFlagFilter(name: String, val uri: String = name) : Filter.CheckBox(name, true)
    private class LanguageFilters(flags: List<SearchFlagFilter>) : Filter.Group<SearchFlagFilter>("Language", flags)
    private class CategoryFilters(flags: List<SearchFlagFilter>) : Filter.Group<SearchFlagFilter>("Category", flags)

    override fun getFilterList() = FilterList(
        SortOrderFilter(getSortOrderURIs()),
        CategoryFilters(getCategoryURIs()),
        LanguageFilters(getLanguageURIs())
    )

    private fun getCategoryURIs() = listOf(
        SearchFlagFilter("Manga", "manga"),
        SearchFlagFilter("Doujinshi", "doujinshi"),
        SearchFlagFilter("Western", "western"),
        SearchFlagFilter("Image Set", "imageset"),
        SearchFlagFilter("Artist CG", "artistcg"),
        SearchFlagFilter("Game CG", "gamecg")
    )

    private fun getSortOrderURIs() = listOf(
        Pair("Latest", "lt"),
        Pair("Popular", "pp"),
        Pair("Downloads", "dl"),
        Pair("Top Rated", "tr")
    )

    private fun getLanguageURIs() = listOf(
        SearchFlagFilter("English", "en"),
        SearchFlagFilter("Japanese", "jp"),
        SearchFlagFilter("Spanish", "es"),
        SearchFlagFilter("French", "fr"),
        SearchFlagFilter("Korean", "kr"),
        SearchFlagFilter("German", "de"),
        SearchFlagFilter("Russian", "ru")
    )
}
