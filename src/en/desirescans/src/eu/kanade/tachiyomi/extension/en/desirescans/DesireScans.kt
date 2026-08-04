package eu.kanade.tachiyomi.extension.en.desirescans

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

@Source
abstract class DesireScans : KeiSource() {

    override val supportsLatest = true

    // Popular

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/api/series?sort=views&page=$page&limit=$PAGE_LIMIT"
        val response = client.get(url)
        return response.parseAs<SeriesListResponse>().toMangasPage()
    }

    // Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/api/series?sort=updatedAt&page=$page&limit=$PAGE_LIMIT"
        val response = client.get(url)
        return response.parseAs<SeriesListResponse>().toMangasPage()
    }

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/api/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", PAGE_LIMIT.toString())

            filters.firstInstanceOrNull<SortFilter>()?.let {
                addQueryParameter("sort", it.selected)
            }

            if (query.isNotBlank()) {
                addQueryParameter("q", query)
            }

            filters.firstInstanceOrNull<GenreFilter>()?.selected?.let {
                if (it.isNotBlank()) addQueryParameter("genre", it)
            }

            filters.firstInstanceOrNull<TypeFilter>()?.selected?.let {
                if (it.isNotBlank()) addQueryParameter("type", it)
            }

            filters.firstInstanceOrNull<StatusFilter>()?.selected?.let {
                if (it.isNotBlank()) addQueryParameter("status", it)
            }
        }.build()

        val response = client.get(url)
        return response.parseAs<SeriesListResponse>().toMangasPage()
    }

    // Details

    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val url = "$baseUrl/series/comic/${manga.url}"
        val response = client.get(url)
        val document = Jsoup.parse(response.body.string())

        val updatedManga = if (fetchDetails) {
            manga.apply {
                val description = document.select("meta[name=description]").attr("content")
                this.description = description

                val genres = document.select("a[href*=/series?genre=]").map { it.text() }
                genre = genres.joinToString()

                status = SManga.UNKNOWN
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val slug = response.request.url.pathSegments.last()
            val chapterLinks = document.select("a[href*=/chapter/]")

            chapterLinks.mapNotNull { link ->
                val href = link.attr("href")
                val chapterMatch = Regex("/chapter/(\\d+)").find(href)
                if (chapterMatch != null) {
                    val chapterNum = chapterMatch.groupValues[1].toInt()
                    SChapter.create().apply {
                        this.url = "$slug/chapter/$chapterNum"
                        name = "Chapter $chapterNum"
                        date_upload = 0L
                    }
                } else {
                    null
                }
            }.distinctBy { it.url }.sortedByDescending {
                it.url.substringAfterLast("/").toIntOrNull() ?: 0
            }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/comic/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/series/comic/${chapter.url}"

    // Pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = "$baseUrl/series/comic/${chapter.url}"
        val response = client.get(url)
        val html = response.body.string()

        val imageRegex = """https://media\.desirescans\.com/series/[^"'\s]+\.(?:jpg|jpeg|png|webp)""".toRegex()
        val images = imageRegex.findAll(html).map { it.value }.distinct().toList()

        return images.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    // Filters

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
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
