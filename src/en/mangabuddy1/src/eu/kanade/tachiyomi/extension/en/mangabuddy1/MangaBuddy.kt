package eu.kanade.tachiyomi.extension.en.mangabuddy1

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

@Source
abstract class MangaBuddy : KeiSource() {

    override val supportsLatest = true

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "20")
            .addQueryParameter("sort", "views")
            .build()

        return client.get(url).use { response ->
            parseMangaList(response)
        }
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "20")
            .addQueryParameter("sort", "latest")
            .build()

        return client.get(url).use { response ->
            parseMangaList(response)
        }
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("searchTerm", query)
            .addQueryParameter("page", page.toString())
            .build()

        return client.get(url).use { response ->
            parseSearchList(response)
        }
    }

    // ============================== Details ==============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.substringAfter("/series/").substringBefore(".")
        val url = "$baseUrl/api/series/$slug"

        val result = client.get(url).use { response ->
            response.parseAs<SeriesDetailResponse>()
        }

        val updatedManga = if (fetchDetails) {
            manga.copyFrom(result.comic)
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            result.chapters.map { it.toSChapter() }
        } else {
            chapters
        }

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    // ============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(baseUrl + chapter.url).use { response ->
        val document = response.asJsoup()
        document.select("img[data-src]").mapIndexed { index, element ->
            Page(index, imageUrl = element.attr("abs:data-src"))
        }
    }

    // ============================ Utilities ==============================

    private fun parseMangaList(response: Response): MangasPage {
        val result = response.parseAs<SeriesListResponse>()
        val mangas = result.comics.map { it.toSManga() }
        val hasNextPage = result.pagination.has_next_page
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseSearchList(response: Response): MangasPage {
        val result = response.parseAs<SearchResponse>()
        val mangas = result.comics.map { it.toSManga() }
        val hasNextPage = result.pagination.has_next_page
        return MangasPage(mangas, hasNextPage)
    }

    private fun SManga.copyFrom(comic: ComicDetail): SManga = apply {
        title = comic.title
        thumbnail_url = comic.cover
        description = comic.description
        author = comic.author
        artist = comic.artist
        status = when (comic.status.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        genre = comic.kind
    }
}
