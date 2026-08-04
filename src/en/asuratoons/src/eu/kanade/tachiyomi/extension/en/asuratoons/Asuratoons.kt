package eu.kanade.tachiyomi.extension.en.asuratoons

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
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class Asuratoons : KeiSource() {

    override val supportsLatest = true

    // Popular Manga
    override suspend fun getPopularManga(page: Int): MangasPage {
        val browseUrl = "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()

        client.get(browseUrl).use { response ->
            val document = response.asJsoup()
            val mangas = document.select("[data-testid=manga-card]").map { element ->
                SManga.create().apply {
                    url = element.selectFirst("a[href^=/manga/]")?.attr("href") ?: ""
                    title = element.selectFirst("[data-testid=manga-card-title]")?.text() ?: ""
                    thumbnail_url = element.selectFirst("img")?.attr("src")
                }
            }

            val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
            val hasNextPage = document.selectFirst("a[href*=page=${currentPage + 1}]") != null

            return MangasPage(mangas, hasNextPage)
        }
    }

    // Latest Updates
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val browseUrl = "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "latest")
            .build()

        client.get(browseUrl).use { response ->
            val document = response.asJsoup()
            val mangas = document.select("[data-testid=manga-card]").map { element ->
                SManga.create().apply {
                    url = element.selectFirst("a[href^=/manga/]")?.attr("href") ?: ""
                    title = element.selectFirst("[data-testid=manga-card-title]")?.text() ?: ""
                    thumbnail_url = element.selectFirst("img")?.attr("src")
                }
            }

            val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
            val hasNextPage = document.selectFirst("a[href*=page=${currentPage + 1}]") != null

            return MangasPage(mangas, hasNextPage)
        }
    }

    // Search
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchUrl = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()

        client.get(searchUrl).use { response ->
            val document = response.asJsoup()
            val mangas = document.select("[data-testid=manga-card]").map { element ->
                SManga.create().apply {
                    url = element.selectFirst("a[href^=/manga/]")?.attr("href") ?: ""
                    title = element.selectFirst("[data-testid=manga-card-title]")?.text() ?: ""
                    thumbnail_url = element.selectFirst("img")?.attr("src")
                }
            }

            val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
            val hasNextPage = document.selectFirst("a[href*=page=${currentPage + 1}]") != null

            return MangasPage(mangas, hasNextPage)
        }
    }

    // Manga Details
    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaUrl = getMangaUrl(manga)

        client.get(mangaUrl).use { response ->
            val document = response.asJsoup()

            val updatedManga = SManga.create().apply {
                this.url = manga.url
                title = manga.title

                if (fetchDetails) {
                    thumbnail_url = document.selectFirst("img[alt*=cover], img[src*=asurasset]")?.attr("src")
                    description = document.selectFirst("meta[name=description]")?.attr("content")
                    author = document.selectFirst("meta[property=og:article:author]")?.attr("content")
                    genre = document.select("a[href^=/category/]").joinToString { it.text() }

                    val statusText = document.selectFirst(".animate-pulse")?.parent()?.text()
                    status = when (statusText?.lowercase()) {
                        "ongoing" -> SManga.ONGOING
                        "completed" -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                }
            }

            val updatedChapters = if (fetchChapters) {
                document.select("a[href*=/chapter/]").map { element ->
                    SChapter.create().apply {
                        url = element.attr("href")
                        name = element.text()
                        chapter_number = extractChapterNumber(url)
                    }
                }.reversed()
            } else {
                chapters
            }

            return SMangaUpdate(updatedManga, updatedChapters)
        }
    }

    private fun extractChapterNumber(url: String): Float {
        val match = Regex("chapter-(\\d+)").find(url)
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    }

    // Chapter Pages
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)

        client.get(chapterUrl).use { response ->
            val document = response.asJsoup()

            return document.select("img[src*=asurasset]").mapIndexed { index, element ->
                Page(index, imageUrl = element.attr("src"))
            }
        }
    }
}
