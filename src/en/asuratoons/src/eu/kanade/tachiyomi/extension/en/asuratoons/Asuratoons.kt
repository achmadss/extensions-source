package eu.kanade.tachiyomi.extension.en.asuratoons

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class Asuratoons : HttpSource() {

    override val name = "Asuratoons"

    override val baseUrl = "https://asuratoons.info"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Popular Manga
    override fun popularMangaRequest(page: Int): Request = if (page == 1) {
        GET(baseUrl, headers)
    } else {
        // Site doesn't have pagination for popular, return empty for pages > 1
        GET("$baseUrl/page-not-found", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }
        return MangasPage(mangas, false)
    }

    private fun popularMangaSelector() = "a[href*=/manga/]"

    private fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val href = element.attr("href")
        val slug = href.substringAfter("/manga/").substringBefore("/")

        manga.url = "/manga/$slug"
        manga.title = element.selectFirst("h2, h3, .title")?.text() ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

        // Try to find thumbnail in nearby img element
        val imgElement = element.selectFirst("img") ?: element.parent()?.selectFirst("img")
        manga.thumbnail_url = imgElement?.attr("src") ?: imgElement?.attr("data-src")

        return manga
    }

    // Latest Updates
    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()

        if (page > 1) {
            url.addPathSegment("page")
            url.addPathSegment(page.toString())
        }

        if (query.isNotBlank()) {
            url.addQueryParameter("s", query)
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(searchMangaSelector()).map { element ->
            searchMangaFromElement(element)
        }
        val hasNextPage = document.selectFirst(searchMangaNextPageSelector()) != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun searchMangaSelector() = "a[href*=/manga/]"

    private fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val href = element.attr("href")
        val slug = href.substringAfter("/manga/").substringBefore("/")

        manga.url = "/manga/$slug"
        manga.title = element.selectFirst("h2, h3, .title")?.text() ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

        val imgElement = element.selectFirst("img") ?: element.parent()?.selectFirst("img")
        manga.thumbnail_url = imgElement?.attr("src") ?: imgElement?.attr("data-src")

        return manga
    }

    private fun searchMangaNextPageSelector() = "a.next, a[rel=next], .pagination a:last-child"

    // Manga Details
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val manga = SManga.create()

        manga.title = document.selectFirst("h1, .entry-title")?.text() ?: ""

        // Get thumbnail from og:image or main image
        manga.thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".thumb img, .cover img, article img")?.attr("src")

        // Get description from meta or content
        manga.description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst(".summary, .description, .entry-content p")?.text()

        // Get author/artist
        manga.author = document.selectFirst(".author, [itemprop=author]")?.text()

        // Get genres
        manga.genre = document.select(".genres a, .genre a, [rel=tag]").joinToString { it.text() }

        // Get status
        val statusText = document.selectFirst(".status, .info span:contains(Status)")?.text()
        manga.status = parseStatus(statusText)

        return manga
    }

    private fun parseStatus(status: String?): Int {
        if (status.isNullOrBlank()) return SManga.UNKNOWN
        return when {
            status.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
            status.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
            status.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            status.contains("Dropped", ignoreCase = true) -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // Chapter List
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(chapterListSelector()).map { chapterFromElement(it) }.reversed()
    }

    private fun chapterListSelector() = "a[href*=/chapter/], .chapter-list a, .chapters a"

    private fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val href = element.attr("href")

        // Extract chapter number from URL
        val chapterNum = href.substringAfter("/chapter/").substringBefore("/")
        chapter.url = href.substringAfter(baseUrl)

        val chapterName = element.selectFirst(".chapter-num, .ch-num")?.text()
            ?: "Chapter $chapterNum"

        chapter.name = chapterName
        chapter.date_upload = parseChapterDate(element.selectFirst(".date, time")?.text())

        return chapter
    }

    private fun parseChapterDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0
        // Simple date parsing - return 0 for now as dates vary
        return 0
    }

    // Page List
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pages = mutableListOf<Page>()

        // Look for reader images
        val imageElements = document.select(".reader img, .chapter-images img, .reading-content img, #readerarea img")

        imageElements.forEachIndexed { index, element ->
            val imageUrl = element.attr("src").ifBlank { element.attr("data-src") }
            if (imageUrl.isNotBlank() && (imageUrl.contains("http") || imageUrl.startsWith("//"))) {
                pages.add(Page(index, imageUrl = imageUrl))
            }
        }

        // If no images found in reader area, try to find them in script data
        if (pages.isEmpty()) {
            val scripts = document.select("script")
            scripts.forEach { script ->
                val data = script.data()
                if (data.contains("images") || data.contains("pages")) {
                    // Extract image URLs from JavaScript
                    val imageRegex = Regex("""(https?://[^"'\s]+\.(?:jpg|jpeg|png|webp))""")
                    imageRegex.findAll(data).forEach { match ->
                        pages.add(Page(pages.size, imageUrl = match.value))
                    }
                }
            }
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun getFilterList() = FilterList()
}
