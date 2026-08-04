package eu.kanade.tachiyomi.extension.en.omanga

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
import keiyoushi.utils.extractNextJs
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response

@Source
abstract class Omanga : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$baseUrl/catalog?sort=by_views&order=desc&page=$page")
        return parseMangaList(response)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.get("$baseUrl/catalog?sort=by_date&order=desc&page=$page")
        return parseMangaList(response)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("page", page.toString())
            .build()

        return parseSearchMangaList(client.get(url))
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaDto = client.get(mangaUrl(manga.url)).extractMangaDetail()
            ?: error("Failed to parse manga details")

        return SMangaUpdate(
            mangaDto.toSManga(),
            mangaDto.toChapters(),
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "manga") return null

        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return client.get(mangaUrl(slug)).extractMangaDetail()?.toSManga()
    }

    override fun getMangaUrl(manga: SManga): String = mangaUrl(manga.url).toString()

    override fun getChapterUrl(chapter: SChapter): String = chapterUrl(chapter).toString()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pageDto = client.get(chapterUrl(chapter)).extractChapterPage() ?: return emptyList()
        return pageDto.toPageList()
    }

    private fun parseMangaList(response: Response): MangasPage {
        val mangaList = response.extractNextJs<MangaListDto> { element ->
            element is JsonObject && "initialItems" in element && "initialHasMore" in element
        } ?: error("Failed to parse manga list")

        return mangaList.toMangasPage()
    }

    private fun parseSearchMangaList(response: Response): MangasPage {
        val mangas = response.asJsoup()
            .select("a[data-id][href^=/manga/]")
            .mapNotNull { element ->
                val title = element.attr("title").ifEmpty { return@mapNotNull null }
                val slug = element.absUrl("href")
                    .toHttpUrlOrNull()
                    ?.pathSegments
                    ?.getOrNull(1)
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                SManga.create().apply {
                    url = slug
                    this.title = title
                    thumbnail_url = element.selectFirst("img")?.absUrl("src")?.ifEmpty { null }
                }
            }

        return MangasPage(mangas, false)
    }

    private fun Response.extractMangaDetail(): MangaDetailDto? = extractNextJs<MangaDetailDto> { element ->
        element is JsonObject &&
            "slug" in element &&
            "title" in element &&
            "chapters" in element
    }

    private fun Response.extractChapterPage(): ChapterPageDto? = extractNextJs<ChapterPageDto> { element ->
        val chapter = (element as? JsonObject)?.get("chapter") as? JsonObject
        chapter != null && "pages" in chapter
    }

    private fun mangaUrl(slug: String): HttpUrl = baseUrl.toHttpUrl().newBuilder()
        .addPathSegment("manga")
        .addPathSegment(slug)
        .build()

    private fun chapterUrl(chapter: SChapter): HttpUrl = baseUrl.toHttpUrl().resolve(chapter.url)
        ?: error("Invalid chapter URL: ${chapter.url}")
}
