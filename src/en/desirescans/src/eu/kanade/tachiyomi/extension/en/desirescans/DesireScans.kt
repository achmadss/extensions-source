package eu.kanade.tachiyomi.extension.en.desirescans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class DesireScans : HttpSource() {

    override val name = "DesireScans"

    override val baseUrl = "https://desirescans.com"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // Popular

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/series?sort=views&page=$page&limit=$PAGE_LIMIT", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<SeriesListResponse>()
        val mangas = data.data.map { it.toSManga() }
        val hasNextPage = data.meta.hasMore
        return MangasPage(mangas, hasNextPage)
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/series?sort=updatedAt&page=$page&limit=$PAGE_LIMIT", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", PAGE_LIMIT.toString())

            val filter = filters.filterIsInstance<SortFilter>().firstOrNull()
            val sortValue = filter?.selected ?: "updatedAt"
            addQueryParameter("sort", sortValue)

            if (query.isNotBlank()) {
                addQueryParameter("q", query)
            }

            filters.filterIsInstance<GenreFilter>().firstOrNull()?.selected?.let {
                if (it.isNotBlank()) addQueryParameter("genre", it)
            }

            filters.filterIsInstance<TypeFilter>().firstOrNull()?.selected?.let {
                if (it.isNotBlank()) addQueryParameter("type", it)
            }

            filters.filterIsInstance<StatusFilter>().firstOrNull()?.selected?.let {
                if (it.isNotBlank()) addQueryParameter("status", it)
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Details

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/series/comic/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())
        val manga = SManga.create()

        // Extract description from meta tag
        val description = document.select("meta[name=description]").attr("content")
        manga.description = description

        // Extract genres
        val genres = document.select("a[href*=/series?genre=]").map { it.text() }
        manga.genre = genres.joinToString()

        manga.status = SManga.UNKNOWN

        return manga
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/comic/${manga.url}"

    // Chapters

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/series/comic/${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())
        val slug = response.request.url.pathSegments.last()

        // Extract chapter links from HTML
        val chapterLinks = document.select("a[href*=/chapter/]")
        val chapters = mutableListOf<SChapter>()

        for (link in chapterLinks) {
            val href = link.attr("href")
            val chapterMatch = Regex("/chapter/(\\d+)").find(href)
            if (chapterMatch != null) {
                val chapterNum = chapterMatch.groupValues[1].toInt()
                val chapter = SChapter.create()
                chapter.url = "$slug/chapter/$chapterNum"
                chapter.name = "Chapter $chapterNum"
                chapter.date_upload = 0L
                chapters.add(chapter)
            }
        }

        // Remove duplicates and sort by chapter number descending
        return chapters.distinctBy { it.url }.sortedByDescending {
            it.url.substringAfterLast("/").toIntOrNull() ?: 0
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/series/comic/${chapter.url}"

    // Pages

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/series/comic/${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val imageRegex = """https://media\.desirescans\.com/series/[^"'\s]+\.(?:jpg|jpeg|png|webp)""".toRegex()
        val images = imageRegex.findAll(html).map { it.value }.distinct().toList()

        return images.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Filters

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
    )

    class SortFilter :
        Filter.Select<String>(
            "Sort By",
            arrayOf(
                "Latest Update",
                "Most Viewed",
                "Recently Added",
                "Title A-Z",
            ),
        ) {
        val selected: String
            get() = when (state) {
                0 -> "updatedAt"
                1 -> "views"
                2 -> "createdAt"
                3 -> "title"
                else -> "updatedAt"
            }
    }

    class GenreFilter :
        Filter.Select<String>(
            "Genre",
            arrayOf(
                "All",
                "Action",
                "Adult",
                "Adventure",
                "Comedy",
                "Drama",
                "Fantasy",
                "Gender Bender",
                "Harem",
                "Historical",
                "Horror",
                "Josei",
                "Mature",
                "Mystery",
                "Psychological",
                "Romance",
                "School Life",
                "Sci-Fi",
                "Seinen",
                "Shoujo",
                "Shounen",
                "Slice of Life",
                "Smut",
                "Sports",
                "Supernatural",
                "Tragedy",
                "Yaoi",
                "Yuri",
            ),
        ) {
        val selected: String
            get() = when (state) {
                0 -> ""
                else -> values[state].lowercase().replace(" ", "-")
            }
    }

    class TypeFilter :
        Filter.Select<String>(
            "Type",
            arrayOf(
                "All",
                "Manga",
                "Manhwa",
                "Manhua",
            ),
        ) {
        val selected: String
            get() = when (state) {
                0 -> ""
                else -> values[state].uppercase()
            }
    }

    class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf(
                "All",
                "Ongoing",
                "Completed",
                "Hiatus",
                "Dropped",
            ),
        ) {
        val selected: String
            get() = when (state) {
                0 -> ""
                else -> values[state].uppercase()
            }
    }

    companion object {
        private const val PAGE_LIMIT = 24
    }
}
