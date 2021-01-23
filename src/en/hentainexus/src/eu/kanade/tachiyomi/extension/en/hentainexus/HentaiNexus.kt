package eu.kanade.tachiyomi.extension.en.hentainexus

import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import com.google.gson.JsonObject
import eu.kanade.tachiyomi.annotations.Nsfw
import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.net.URLEncoder

@Nsfw
class HentaiNexus : ParsedHttpSource() {

    override val name = "HentaiNexus"

    override val baseUrl = "https://hentainexus.com"

    override val lang = "en"

    override val supportsLatest = true

    private val rateLimitInterceptor = RateLimitInterceptor(4)

    private val gson = Gson()

    override val client: OkHttpClient = network.client.newBuilder()
        .addNetworkInterceptor(rateLimitInterceptor)
        .build()

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", USER_AGENT)
    }

    override fun latestUpdatesSelector() = "div.container div.column"

    override fun latestUpdatesRequest(page: Int) = pagedRequest(baseUrl, page)

    override fun latestUpdatesFromElement(element: Element): SManga {
        val item = element.select("div.column a")
        return SManga.create().apply {
            setUrlWithoutDomain(item.attr("href"))
            title = item.text()
            thumbnail_url = element.select("figure.image > img").attr("src")
        }
    }

    override fun latestUpdatesNextPageSelector() = "nav.pagination > a.pagination-next"

    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)

    override fun popularMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun popularMangaSelector() = latestUpdatesSelector()

    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {

        val exactMatchFilters = filters.filterIsInstance<ExactMatchFilter>().filter { it.state.isNotBlank() }

        val queryParam = when {
            exactMatchFilters.size > 1 || (exactMatchFilters.size == 1 && query.isNotEmpty()) ->
                throw Exception("You cannot combine filters or use text search with filters!")
            query.isNotEmpty() -> URLEncoder.encode(query, "UTF-8")
            exactMatchFilters.size == 1 -> with(exactMatchFilters.first()) {
                "${this.uri}:%22${URLEncoder.encode(this.state, "UTF-8")}%22"
            }
            else -> null
        }

        return queryParam?.let { pagedRequest(baseUrl, page, queryParam) } ?: latestUpdatesRequest(page)
    }

    private fun pagedRequest(url: String, page: Int, query: String? = null): Request {
        // The site redirects page 1 -> url-without-page so we do this redirect early for optimization
        val urlWithPage = if (page == 1) url else "$url/page/$page"
        return GET(query?.let { "$urlWithPage/?q=$it" } ?: urlWithPage)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_ID_SEARCH)) {
            val id = query.removePrefix(PREFIX_ID_SEARCH)
            client.newCall(GET("$baseUrl/view/$id", headers)).asObservableSuccess()
                .map { MangasPage(listOf(mangaDetailsParse(it).apply { url = "/view/$id" }), false) }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaSelector() = latestUpdatesSelector()

    override fun searchMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div[class=\"column\"]")

        return SManga.create().apply {
            title = infoElement.select("h1").text()
            author = infoElement.select("td.viewcolumn:containsOwn(Artist) + td").text()
            artist = author
            status = SManga.COMPLETED
            genre = infoElement.select("td.viewcolumn:containsOwn(Tags) + td a")
                .joinToString(", ") { it.text() }
            description = buildDescription(document)
            thumbnail_url = infoElement.prev().select("figure.image img").attr("src")
        }
    }

    private fun buildDescription(infoElement: Element): String {

        return listOf(
            "Description",
            "Magazine",
            "Language",
            "Parody",
            "Publisher"
        ).map { it to infoElement.select("td.viewcolumn:containsOwn($it) + td").text() }
            .filter { !it.second.isNullOrEmpty() }
            .joinToString("\n\n") { "${it.first}:\n${it.second}" }
    }

    // Chapters
    override fun chapterListParse(response: Response): List<SChapter> {
        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                chapter_number = 1f
                url = "${response.request().url().toString().replace("/view/", "/read/")}#001"
                page_count = response.asJsoup().select("td.viewcolumn:containsOwn(Pages) + td").text().toInt()
            }
        )
    }

    override fun chapterListSelector() = throw UnsupportedOperationException("Not used")

    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException("Not used")

    // Pages

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val pageBaseUrl = chapter.url.substringBefore("#")
        return client.newCall(GET(pageBaseUrl))
            .asObservableSuccess()
            .map {

                val encodedReaderData = it.asJsoup().select("script:not([src])").html()
                    .substringAfter("initReader(\"")
                    .substringBefore("\", \"") // right before the 2nd argument

                gson.fromJson<JsonObject>(decodeReader(encodedReaderData))["pages"].asJsonArray.mapIndexed { index, p ->
                    Page(index, "$pageBaseUrl#${it.toString().padStart(3, '0')}", p.asString)
                }
            }
    }

    override fun pageListParse(document: Document): List<Page> = throw UnsupportedOperationException("Not used")

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    override fun getFilterList() = FilterList(
        Filter.Header("Only one filter may be used at a time"),
        Filter.Separator(),
        ExactMatchFilter("Artist", "artist"),
        ExactMatchFilter("Tag", "tag"),
        ExactMatchFilter("Parody", "parody"),
        ExactMatchFilter("Magazine", "magazine"),
        ExactMatchFilter("Language", "language"),
        ExactMatchFilter("Publisher", "publisher")
    )

    class ExactMatchFilter(label: String, val uri: String) : Filter.Text("Search by $label (must be exact match)")

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/81.0.4044.122 Safari/537.36"
    }
}
