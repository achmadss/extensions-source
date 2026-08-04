package eu.kanade.tachiyomi.extension.en.mangahome

import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class MangaHome : HttpSource() {

    override val name = "MangaHome"

    override val baseUrl = "https://www.mangahome.com"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(1)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Popular

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/rank?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangaList = document.select(".postbig").map { popularMangaFromElement(it) }
        val hasNextPage = document.selectFirst("a.next") != null
        return MangasPage(mangaList, hasNextPage)
    }

    private fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val linkElement = element.selectFirst("a")!!
        setUrlWithoutDomain(linkElement.attr("href"))
        title = element.selectFirst(".postbig-info h3")?.text() ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangaList = document.select(".post").map { latestUpdatesFromElement(it) }
        val hasNextPage = document.selectFirst("a.next") != null
        return MangasPage(mangaList, hasNextPage)
    }

    private fun latestUpdatesFromElement(element: Element): SManga = SManga.create().apply {
        val linkElement = element.selectFirst(".post-cover a")!!
        setUrlWithoutDomain(linkElement.attr("href"))
        title = element.selectFirst(".post-cover a img")?.attr("alt") ?: ""
        thumbnail_url = element.selectFirst(".post-cover a img")?.attr("src")
    }

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("genre", filter.values[filter.state])
                    }
                }
                is StatusFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("status", filter.values[filter.state])
                    }
                }
                else -> {}
            }
        }

        url.addQueryParameter("name", query)
        url.addQueryParameter("page", page.toString())

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangaList = document.select(".manga-list .post").map { searchMangaFromElement(it) }
        val hasNextPage = document.selectFirst("a.next") != null
        return MangasPage(mangaList, hasNextPage)
    }

    private fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        val linkElement = element.selectFirst(".post-cover a")!!
        setUrlWithoutDomain(linkElement.attr("href"))
        title = element.selectFirst(".post-cover a img")?.attr("alt") ?: ""
        thumbnail_url = element.selectFirst(".post-cover a img")?.attr("src")
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            val detailElement = document.selectFirst(".manga-detail")

            title = detailElement?.selectFirst("h1")?.text() ?: ""

            val infoElement = detailElement?.selectFirst(".detail-info")
            author = infoElement?.select("p")?.find { it.text().contains("Author") }
                ?.selectFirst("a")?.text()
            artist = infoElement?.select("p")?.find { it.text().contains("Artist") }
                ?.selectFirst("a")?.text()

            val statusText = infoElement?.select("p")?.find { it.text().contains("Status") }
                ?.text() ?: ""
            status = when {
                statusText.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                statusText.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            genre = infoElement?.select(".detail-info-tag a")?.joinToString { it.text() }

            description = detailElement?.selectFirst(".detail-info-content")?.text()

            thumbnail_url = detailElement?.selectFirst(".detail-cover")?.attr("src")
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(".detail-chlist li").map { chapterFromElement(it) }
    }

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val linkElement = element.selectFirst("a")!!
        setUrlWithoutDomain(linkElement.attr("href"))
        name = linkElement.selectFirst(".mobile-none")?.text() ?: linkElement.text()

        val dateText = element.selectFirst(".time")?.text()
        date_upload = parseChapterDate(dateText)
    }

    private fun parseChapterDate(date: String?): Long {
        if (date == null) return 0

        return try {
            SimpleDateFormat("MMM dd,yyyy", Locale.ENGLISH).parse(date)?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }

    // Pages

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pages = mutableListOf<Page>()

        val chapterId = document.selectFirst("script:containsData(chapter_id)")?.data()
            ?.substringAfter("chapter_id=")?.substringBefore(";")?.trim() ?: return pages

        val imageCount = document.selectFirst("script:containsData(imagecount)")?.data()
            ?.substringAfter("imagecount=")?.substringBefore(";")?.trim()?.toIntOrNull() ?: return pages

        val quickJs = QuickJs.create()

        try {
            for (i in 1..imageCount) {
                val requestUrl = "$baseUrl/manga${document.location().substringAfter(baseUrl)}/chapterfun.ashx?cid=$chapterId&page=$i&key="

                val request = Request.Builder()
                    .url(requestUrl)
                    .headers(headers)
                    .addHeader("Referer", document.location())
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .build()

                val pageResponse = client.newCall(request).execute()
                val responseBody = pageResponse.body.string()

                if (responseBody.isNotEmpty()) {
                    val deobfuscatedScript = quickJs.evaluate(responseBody.removePrefix("eval"))?.toString() ?: continue

                    val pixStart = deobfuscatedScript.indexOf("pix=\"") + 5
                    val pixEnd = deobfuscatedScript.indexOf("\"", pixStart)
                    val pix = deobfuscatedScript.substring(pixStart, pixEnd)

                    val pvalueStart = deobfuscatedScript.indexOf("pvalue=[\"") + 9
                    val pvalueEnd = deobfuscatedScript.indexOf("\"]", pvalueStart)
                    val pvalue = deobfuscatedScript.substring(pvalueStart, pvalueEnd).split("\",\"")

                    val imageUrl = "https:$pix${pvalue.firstOrNull() ?: continue}"
                    pages.add(Page(i - 1, imageUrl = imageUrl))
                }
            }
        } finally {
            quickJs.close()
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String = ""

    // Filters

    override fun getFilterList() = FilterList(
        GenreFilter(),
        StatusFilter(),
    )

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            arrayOf(
                "All",
                "Action",
                "Adventure",
                "Comedy",
                "Drama",
                "Fantasy",
                "Horror",
                "Romance",
                "School Life",
                "Shoujo",
                "Shounen",
                "Slice of Life",
                "Supernatural",
            ),
        )

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf(
                "All",
                "Ongoing",
                "Completed",
            ),
        )
}
