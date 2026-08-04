package eu.kanade.tachiyomi.extension.en.fsicomics

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

@Source
abstract class FsiComics : KeiSource() {

    override val supportsLatest = true

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/all-porn-comics/"
        } else {
            "$baseUrl/all-porn-comics/page/$page/"
        }
        val response = client.get(url)
        return parseMangaList(response)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/all-porn-comics/"
        } else {
            "$baseUrl/all-porn-comics/page/$page/"
        }
        val response = client.get(url)
        return parseMangaList(response)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (page == 1) {
            "$baseUrl/?s=$query"
        } else {
            "$baseUrl/page/$page/?s=$query"
        }
        val response = client.get(url)
        return parseMangaList(response)
    }

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.p-wrap.p-grid").mapNotNull { element ->
            val linkElement = element.selectFirst("h4.entry-title a.p-url") ?: return@mapNotNull null
            SManga.create().apply {
                url = linkElement.attr("href").toHttpUrl().encodedPath
                title = linkElement.text()
                thumbnail_url = element.selectFirst("img.featured-img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst("a.next.page-numbers") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val response = client.get(url)
        val document = response.asJsoup()
        return SManga.create().apply {
            this.url = url.encodedPath
            title = document.selectFirst("h1.entry-title")?.text() ?: return null
            thumbnail_url = document.selectFirst("div.p-featured img")?.attr("abs:src")
            description = document.selectFirst("div.entry-content p")?.text()
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = getMangaUrl(manga).toHttpUrl()
        val response = client.get(url)
        val document = response.asJsoup()

        val updatedManga = SManga.create().apply {
            this.url = manga.url
            title = document.selectFirst("h1.entry-title")?.text() ?: manga.title
            thumbnail_url = document.selectFirst("div.p-featured img")?.attr("abs:src")
            description = document.selectFirst("div.entry-content p")?.text()
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }

        val updatedChapters = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    this.url = manga.url
                    name = "Chapter"
                    date_upload = 0L
                },
            )
        } else {
            chapters
        }

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = getChapterUrl(chapter).toHttpUrl()
        val response = client.get(url)
        val document = response.asJsoup()

        return document.select("div.entry-content img").mapIndexedNotNull { index, element ->
            val imageUrl = element.attr("data-orig-file").ifEmpty { element.attr("abs:src") }
            if (imageUrl.isBlank() ||
                imageUrl.contains("fsi-comics-ratina") ||
                imageUrl.contains("fsicomics-telegram") ||
                imageUrl.contains("fsi-comics-mobile")
            ) {
                null
            } else {
                Page(index, imageUrl = imageUrl)
            }
        }
    }
}
