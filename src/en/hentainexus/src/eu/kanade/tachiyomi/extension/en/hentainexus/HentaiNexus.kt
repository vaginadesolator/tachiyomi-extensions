package eu.kanade.tachiyomi.extension.en.hentainexus

import eu.kanade.tachiyomi.annotations.Nsfw
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

    override val client: OkHttpClient = network.cloudflareClient

    override fun latestUpdatesSelector() = "div.container div.column"

    override fun latestUpdatesRequest(page: Int) = pagedRequest("$baseUrl/", page)

    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        val item = element.select("div.column a")

        manga.url = item.attr("href")
        manga.title = item.text()
        manga.thumbnail_url = element.select("figure.image > img").attr("src")

        return manga
    }

    override fun latestUpdatesNextPageSelector() = "nav.pagination > a.pagination-next"

    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)

    override fun popularMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun popularMangaSelector() = latestUpdatesSelector()

    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url: String? = null
        var queryString: String? = null
        fun requireNoUrl() = require(url == null && queryString == null) {
            "You cannot combine filters or use text search with filters!"
        }

        filters.findInstance<ArtistFilter>()?.let { f ->
            if (f.state.isNotBlank()) {
                requireNoUrl()
                url = "/"
                queryString = "q=artist:%22${URLEncoder.encode(f.state, "UTF-8")}%22"
            }
        }

        filters.findInstance<TagFilter>()?.let { f ->
            if (f.state.isNotBlank()) {
                requireNoUrl()
                url = "/"
                queryString = "q=tag:%22${URLEncoder.encode(f.state, "UTF-8")}%22"
            }
        }

        if (query.isNotBlank()) {
            requireNoUrl()
            url = "/"
            queryString = "q=" + URLEncoder.encode(query, "UTF-8")
        }

        return url?.let {
            pagedRequest("$baseUrl$url", page, queryString)
        } ?: latestUpdatesRequest(page)
    }

    private fun pagedRequest(url: String, page: Int, queryString: String? = null): Request {
        // The site redirects page 1 -> url-without-page so we do this redirect early for optimization
        val builtUrl = if (page == 1) url else "${url}page/$page"
        return GET(if (queryString != null) "$builtUrl?$queryString" else builtUrl)
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

    override fun mangaDetailsRequest(manga: SManga): Request {
        if (manga.url.startsWith("http")) {
            return GET(manga.url, headers)
        }
        return super.mangaDetailsRequest(manga)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.column")
        val manga = SManga.create()
        val genres = mutableListOf<String>()

        document.select("td.viewcolumn:containsOwn(Tags) + td a").forEach { element ->
            val genre = element.text()
            genres.add(genre)
        }

        manga.title = infoElement.select("h1").text()
        manga.author = infoElement.select("td.viewcolumn:containsOwn(Artist) + td").text()
        manga.artist = infoElement.select("td.viewcolumn:containsOwn(Artist) + td").text()
        manga.status = SManga.COMPLETED
        manga.genre = genres.joinToString(", ")
        manga.description = getDesc(document)
        manga.thumbnail_url = document.select("figure.image > img").attr("src")

        return manga
    }

    private fun getDesc(document: Document): String {
        val infoElement = document.select("div.column")
        val stringBuilder = StringBuilder()
        val description = infoElement.select("td.viewcolumn:containsOwn(Description) + td").text()
        val magazine = infoElement.select("td.viewcolumn:containsOwn(Magazine) + td").text()
        val parodies = infoElement.select("td.viewcolumn:containsOwn(Parody) + td").text()
        val publisher = infoElement.select("td.viewcolumn:containsOwn(Publisher) + td").text()
        val pagess = infoElement.select("td.viewcolumn:containsOwn(Pages) + td").text()

        stringBuilder.append(description)
        stringBuilder.append("\n\n")

        stringBuilder.append("Magazine: ")
        stringBuilder.append(magazine)
        stringBuilder.append("\n\n")

        stringBuilder.append("Parodies: ")
        stringBuilder.append(parodies)
        stringBuilder.append("\n\n")

        stringBuilder.append("Publisher: ")
        stringBuilder.append(publisher)
        stringBuilder.append("\n\n")

        stringBuilder.append("Pages: ")
        stringBuilder.append(pagess)

        return stringBuilder.toString()
    }

    override fun chapterListRequest(manga: SManga): Request {
        if (manga.url.startsWith("http")) {
            return GET(manga.url, headers)
        }
        return super.chapterListRequest(manga)
    }

    override fun chapterListSelector() = "div.container nav.depict-button-set"

    // Chapters
    override fun chapterListParse(response: Response): List<SChapter> {
        return listOf(
            SChapter.create().apply {
                name = "Read Online: Chapter 0"
                // page path with a marker at the end
                url = "${response.request().url().toString().replace("/view/", "/read/")}#"
                // number of pages
                url += response.asJsoup().select("td.viewcolumn:containsOwn(Pages) + td").text()
            }
        )
    }

    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException("Not used")

    // Pages
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        // split the "url" to get the page path and number of pages
        return chapter.url.split("#").let { list ->
            // repeat() turns 1 -> 001 and 10 -> 010
            Observable.just(listOf(1..list[1].toInt()).flatten().map { Page(it, list[0] + "/${"0".repeat(maxOf(3 - it.toString().length, 0))}$it") })
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        if (chapter.url.startsWith("http")) {
            return GET(chapter.url, headers)
        }
        return super.pageListRequest(chapter)
    }

    override fun pageListParse(document: Document): List<Page> = throw UnsupportedOperationException("Not used")

    override fun imageUrlParse(document: Document): String {
        return document.select("img#currImage").attr("abs:src")
    }

    override fun getFilterList() = FilterList(
        Filter.Header("Only one filter may be used at a time."),
        Filter.Separator(),
        ArtistFilter(),
        TagFilter()
    )

    class ArtistFilter : Filter.Text("Search by Artist (must be exact match)")
    class TagFilter : Filter.Text("Search by Tag (must be exact match)")

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
    }
}

private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T
