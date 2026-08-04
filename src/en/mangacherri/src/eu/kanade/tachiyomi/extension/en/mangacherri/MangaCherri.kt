package eu.kanade.tachiyomi.extension.en.mangacherri

import eu.kanade.tachiyomi.source.model.Filter
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
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

@Source
abstract class MangaCherri : KeiSource() {

    override val supportsLatest = true

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/weekly-manga.php".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        val document = client.get(url).asJsoup()
        val mangas = parseMangaList(document)
        val hasNextPage = hasNextPage(document, page)
        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/new-chapters.php".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        val document = client.get(url).asJsoup()
        val mangas = parseMangaList(document)
        val hasNextPage = hasNextPage(document, page)
        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val selectedGenre = genreFilter?.selectedGenre

        val url = when {
            selectedGenre != null -> "$baseUrl/genre.php".toHttpUrl().newBuilder().apply {
                addQueryParameter("genre", selectedGenre)
                if (page > 1) addQueryParameter("page", page.toString())
            }.build()
            query.isNotBlank() -> "$baseUrl/home.php".toHttpUrl().newBuilder().apply {
                addQueryParameter("search", query)
                if (page > 1) addQueryParameter("page", page.toString())
            }.build()
            else -> "$baseUrl/new-manga.php".toHttpUrl().newBuilder().apply {
                if (page > 1) addQueryParameter("page", page.toString())
            }.build()
        }

        val document = client.get(url).asJsoup()
        val mangas = parseMangaList(document)
        val hasNextPage = hasNextPage(document, page)
        return MangasPage(mangas, hasNextPage)
    }

    override val supportsFilterFetching = false

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?) = FilterList(GenreFilter())

    // =========================== Manga Details ============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = "$baseUrl/${manga.url}"
        val document = client.get(url).asJsoup()

        val updatedManga = if (fetchDetails) {
            parseMangaDetails(document, manga)
        } else {
            manga
        }

        val chaptersList = if (fetchChapters || chapters.isEmpty()) {
            parseChapterList(document, manga.url)
        } else {
            chapters
        }

        return SMangaUpdate(manga = updatedManga, chapters = chaptersList)
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("h1, h2")?.text() ?: manga.title

        val infoElements = document.select("div.series-info p, div.manga-info p, div.info p")
        for (p in infoElements) {
            val text = p.text().lowercase()
            when {
                text.startsWith("author") -> author = p.select("a").firstOrNull()?.text() ?: p.ownText().substringAfter(":").trim()
                text.startsWith("artist") -> artist = p.select("a").firstOrNull()?.text() ?: p.ownText().substringAfter(":").trim()
                text.startsWith("status") -> {
                    val statusText = p.select("a").firstOrNull()?.text() ?: p.ownText().substringAfter(":").trim()
                    status = parseStatus(statusText)
                }
                text.startsWith("genre") -> genre = p.select("a").joinToString { it.text() }
            }
        }

        description = document.select("div.description p, div.summary p, div.series-summary p").firstOrNull()?.text()

        thumbnail_url = document.selectFirst("div.series-image img, div.cover img, img[src*=mangas/main]")?.attr("abs:src")
            ?: manga.thumbnail_url
    }

    private fun parseChapterList(document: Document, mangaUrl: String): List<SChapter> {
        return document.select("div.chapter-list a, ul.chapter-list a, a.ch-link").mapNotNull { link ->
            val href = link.attr("href")
            val httpUrl = href.toHttpUrl()
            val pathSegments = httpUrl.pathSegments

            if (pathSegments.size < 2) return@mapNotNull null

            val chapterId = pathSegments.last()
            if (!chapterId.all { it.isDigit() }) return@mapNotNull null

            val chapterName = link.text().trim()

            SChapter.create().apply {
                this.url = "$mangaUrl/$chapterId"
                name = chapterName.ifEmpty { "Chapter $chapterId" }
                date_upload = 0L
            }
        }.reversed()
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/${chapter.url}"

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = "$baseUrl/${chapter.url}"
        val document = client.get(url).asJsoup()

        return document.select("div.reader img, div.chapter-images img, div#reader img, img[src*=mangas/]").mapIndexed { index, img ->
            val imageUrl = img.attr("abs:src")
            Page(index, imageUrl = imageUrl)
        }
    }

    // ============================= URL Handling ===========================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath.substring(1)
        if (path.isEmpty() || path.contains(".php")) return null

        val manga = SManga.create().apply {
            this.url = path
        }

        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga.apply {
            initialized = true
            this.url = path
        }
    }

    // ============================= Helpers ================================

    private fun parseMangaList(document: Document): List<SManga> {
        return document.select("div.row > div[class*=col] a[href], .manga-item a, .series-item a").mapNotNull { element ->
            val href = element.attr("href")
            val httpUrl = href.toHttpUrl()
            val path = httpUrl.encodedPath.substring(1)

            if (path.isEmpty() || path.contains(".php") || path.contains("/")) return@mapNotNull null

            val img = element.selectFirst("img") ?: return@mapNotNull null
            val title = element.attr("title").ifEmpty { img.attr("alt") }
            val thumbnailUrl = img.attr("abs:src")

            SManga.create().apply {
                this.url = path
                this.title = title
                this.thumbnail_url = thumbnailUrl
            }
        }.distinctBy { it.url }
    }

    private fun hasNextPage(document: Document, currentPage: Int): Boolean = document.select("a.page-link, a.pagination-link, nav a").any { link ->
        val href = link.attr("href")
        href.contains("page=${currentPage + 1}")
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("ongoing", ignoreCase = true) || status.contains("publishing", ignoreCase = true) -> SManga.ONGOING
        status.contains("complete", ignoreCase = true) || status.contains("finished", ignoreCase = true) -> SManga.COMPLETED
        status.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    class GenreFilter :
        Filter.Select<String>(
            "Genre",
            GENRES.map { it.display }.toTypedArray(),
        ) {
        val selectedGenre: String?
            get() = GENRES[state].id.takeIf { it.isNotEmpty() }
    }

    class Genre(val display: String, val id: String)

    companion object {
        private val GENRES = listOf(
            Genre("<Select>", ""),
            Genre("Adventure", "Adventure"),
            Genre("Animals", "Animals"),
            Genre("Comedy", "Comedy"),
            Genre("Drama", "Drama"),
            Genre("Fantasy", "Fantasy"),
            Genre("Gyaru", "Gyaru"),
            Genre("Isekai", "Isekai"),
            Genre("Josei", "Josei"),
            Genre("Magic", "Magic"),
            Genre("Manhua", "Manhua"),
            Genre("Manhwa", "Manhwa"),
            Genre("Music", "Music"),
            Genre("Mystery", "Mystery"),
            Genre("Office", "Office"),
            Genre("Parody", "Parody"),
            Genre("Psychological", "Psychological"),
            Genre("Romance", "Romance"),
            Genre("School", "School"),
            Genre("Sci-fi", "Sci-fi"),
            Genre("Seinen", "Seinen"),
            Genre("Shoujo", "Shoujo"),
            Genre("Shounen", "Shounen"),
            Genre("Slice of Life", "Slice of Life"),
            Genre("Sports", "Sports"),
            Genre("Supernatural", "Supernatural"),
        )
    }
}
