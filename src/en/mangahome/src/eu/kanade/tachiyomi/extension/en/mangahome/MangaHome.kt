package eu.kanade.tachiyomi.extension.en.mangahome

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
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class MangaHome : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/directory/$page.html?views.az"
        val document = client.get(url).asJsoup()
        val mangas = document.select("ul.manga-list > li").map { element ->
            SManga.create().apply {
                val coverLink = element.selectFirst("a.post-cover")
                setUrlWithoutDomain(coverLink!!.attr("href"))
                title = coverLink.attr("title")
                thumbnail_url = coverLink.selectFirst("img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.select("a.next").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/latest/shoujo/$page.html"
        val document = client.get(url).asJsoup()
        val mangas = document.select("ul.manga-list > li").map { element ->
            SManga.create().apply {
                val coverLink = element.selectFirst("a.post-cover")
                setUrlWithoutDomain(coverLink!!.attr("href"))
                title = coverLink.attr("title")
                thumbnail_url = coverLink.selectFirst("img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.select("a.next").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/search?name=$query&page=$page"
        val document = client.get(url).asJsoup()
        val mangas = document.select("ul.manga-list > li").map { element ->
            SManga.create().apply {
                val coverLink = element.selectFirst("a.post-cover")
                setUrlWithoutDomain(coverLink!!.attr("href"))
                title = coverLink.attr("title")
                thumbnail_url = coverLink.selectFirst("img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.select("a.next").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = null

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}").asJsoup()

        val updatedManga = if (fetchDetails) {
            SManga.create().apply {
                title = document.selectFirst("h1")?.text() ?: manga.title
                author = document.select("span:containsOwn(Author) + span a").joinToString { it.text() }
                artist = document.select("span:containsOwn(Artist) + span a").joinToString { it.text() }

                val statusText = document.select("span:containsOwn(Status)").first()?.parent()?.text() ?: ""
                status = parseStatus(statusText)

                genre = document.select("span:containsOwn(Genre) ~ a").joinToString { it.text() }
                description = document.select("span:containsOwn(Summary)").first()?.parent()?.ownText() ?: ""
                thumbnail_url = document.selectFirst("img.detail-cover")?.attr("abs:src") ?: manga.thumbnail_url
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            document.select("ul.detail-chlist > li").map { element ->
                SChapter.create().apply {
                    val link = element.selectFirst("a")
                    setUrlWithoutDomain(link!!.attr("href"))
                    name = link.text()
                    date_upload = parseDate(element.selectFirst("span.time")?.text() ?: "")
                }
            }
        } else {
            chapters
        }

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
        status.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private val dateFormat = SimpleDateFormat("MMM dd,yyyy", Locale.US)

    private fun parseDate(date: String): Long = dateFormat.tryParse(date)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}").asJsoup()
        val html = document.html()

        val imageCount = Regex("""var\s+imagecount\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val currentRoot = Regex("""var\s+currentroot\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""

        return (1..imageCount).map { i ->
            Page(i - 1, url = "$baseUrl$currentRoot/$i")
        }
    }

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList()
}
