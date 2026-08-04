package eu.kanade.tachiyomi.extension.en.omanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.Response
import org.jsoup.Jsoup

@Source
abstract class Omanga : KeiSource() {

    // ================================ Popular Manga =====================================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/catalog?sort=by_views&order=desc&page=$page"
        val response = client.get(url)
        return parseMangaList(response)
    }

    // ================================ Latest Updates ====================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/catalog?sort=by_date&order=desc&page=$page"
        val response = client.get(url)
        return parseMangaList(response)
    }

    // ================================ Search ============================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/search?query=$query&page=$page"
        val response = client.get(url)
        return parseMangaList(response)
    }

    // ================================ Manga Details =====================================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = "$baseUrl/manga/${manga.url}"
        val response = client.get(url)
        val html = response.body.string()

        val mangaDto = extractMangaDetail(html)
            ?: throw Exception("Failed to parse manga details")

        val updatedManga = SManga.create().apply {
            setUrlWithoutDomain(mangaDto.slug)
            title = mangaDto.title
            author = mangaDto.author
            artist = mangaDto.artist
            description = mangaDto.description
            genre = mangaDto.genres.joinToString()
            status = parseStatus(mangaDto.status)
            thumbnail_url = mangaDto.poster
        }

        val newChapters = if (fetchChapters) {
            mangaDto.chapters.map { chapterDto ->
                SChapter.create().apply {
                    setUrlWithoutDomain("/manga/${mangaDto.slug}/chapter/${chapterDto.number}")
                    name = buildChapterName(chapterDto)
                    date_upload = parseDate(chapterDto.createdAt)
                    chapter_number = chapterDto.number.toFloat()
                }
            }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, newChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != "omanga.to") return null
        val pathSegments = url.pathSegments
        if (pathSegments.size < 2 || pathSegments[0] != "manga") return null

        val slug = pathSegments[1]
        val response = client.get("$baseUrl/manga/$slug")
        val html = response.body.string()

        val mangaDto = extractMangaDetail(html) ?: return null

        return SManga.create().apply {
            setUrlWithoutDomain(slug)
            title = mangaDto.title
            author = mangaDto.author
            artist = mangaDto.artist
            description = mangaDto.description
            genre = mangaDto.genres.joinToString()
            status = parseStatus(mangaDto.status)
            thumbnail_url = mangaDto.poster
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    // ================================ Chapter Pages =====================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = baseUrl + chapter.url
        val response = client.get(url)
        val html = response.body.string()

        val images = extractChapterImages(html)
        return images.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    // ================================ Parsing ===========================================

    private fun parseMangaList(response: Response): MangasPage {
        val html = response.body.string()
        val document = Jsoup.parse(html)

        // Extract RSC data
        val rscData = document.select("script").map { it.data() }.joinToString("\n")

        // Find manga entries in the RSC data
        val mangaPattern = Regex("""\{"id":(\d+),"title":"([^"]+)","slug":"([^"]+)","poster":"([^"]+)","type":"([^"]+)","genres":\[([^\]]*)\],"rating":(\d+),"views":(\d+),"votes":(\d+)""")
        val matches = mangaPattern.findAll(rscData)

        val mangas = matches.map { match ->
            val genres = match.groupValues[6]
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }

            SManga.create().apply {
                setUrlWithoutDomain(match.groupValues[3])
                title = match.groupValues[2]
                thumbnail_url = match.groupValues[4]
                genre = genres.joinToString()
            }
        }.toList()

        // Check if there are more pages (simple heuristic: if we got 36 items, there might be more)
        val hasNextPage = mangas.size >= 36

        return MangasPage(mangas, hasNextPage)
    }

    private fun extractMangaDetail(html: String): MangaDetailDto? {
        val document = Jsoup.parse(html)
        val rscData = document.select("script").map { it.data() }.joinToString("\n")

        // Find the manga detail object with chapters
        val detailPattern = Regex(""""author":"([^"]+)","artist":"([^"]+)","translator":"[^"]*","status":"([^"]+)","ageRating":"[^"]*","altNames":\[[^\]]*\],"chapters":\[(\{[^]]+})\]""")
        val detailMatch = detailPattern.find(rscData) ?: return null

        val chaptersJson = "[${detailMatch.groupValues[4]}]"
        val chapters = try {
            chaptersJson.parseAs<List<ChapterDto>>()
        } catch (e: Exception) {
            emptyList()
        }

        // Extract slug from URL pattern
        val slugPattern = Regex(""""slug":"([^"]+)"""")
        val slug = slugPattern.find(rscData)?.groupValues?.get(1) ?: ""

        // Extract title
        val titlePattern = Regex(""""mangaTitle":"([^"]+)"""")
        val title = titlePattern.find(rscData)?.groupValues?.get(1) ?: ""

        // Extract poster
        val posterPattern = Regex(""""poster":"(https://opics\.online/media/covers/[^"]+)"""")
        val poster = posterPattern.find(rscData)?.groupValues?.get(1) ?: ""

        // Extract description
        val descPattern = Regex(""""description":"([^"]*(?:\\.[^"]*)*)"""")
        val description = descPattern.find(rscData)?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?: ""

        // Extract genres
        val genresPattern = Regex(""""genres":\[([^\]]+)\]""")
        val genres = genresPattern.find(rscData)?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().removeSurrounding("\"") }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        return MangaDetailDto(
            slug = slug,
            title = title,
            author = detailMatch.groupValues[1],
            artist = detailMatch.groupValues[2],
            status = detailMatch.groupValues[3],
            description = description,
            poster = poster,
            genres = genres,
            chapters = chapters,
        )
    }

    private fun extractChapterImages(html: String): List<String> {
        val document = Jsoup.parse(html)
        val rscData = document.select("script").map { it.data() }.joinToString("\n")

        // Find all chapter image URLs
        val imagePattern = Regex("""https://opics\.online/media/chapters/[^"\\]+\.webp""")
        return imagePattern.findAll(rscData)
            .map { it.value }
            .distinct()
            .toList()
    }

    private fun buildChapterName(chapter: ChapterDto): String {
        val name = StringBuilder("Chapter ${chapter.number}")
        if (chapter.title != null) {
            name.append(" - ${chapter.title}")
        }
        if (chapter.translator != null) {
            name.append(" [${chapter.translator}]")
        }
        return name.toString()
    }

    private fun parseStatus(status: String): Int = when (status.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "dropped" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun parseDate(dateStr: String): Long = try {
        // Handle the $D prefix from RSC data
        val cleanDate = dateStr.removePrefix("\$D")
        java.time.Instant.parse(cleanDate).toEpochMilli()
    } catch (e: Exception) {
        0L
    }

    // ================================ DTOs ==============================================

    @Serializable
    private class MangaDetailDto(
        val slug: String,
        val title: String,
        val author: String,
        val artist: String,
        val status: String,
        val description: String,
        val poster: String,
        val genres: List<String>,
        val chapters: List<ChapterDto>,
    )

    @Serializable
    private class ChapterDto(
        val id: Int,
        val mangaId: Int,
        val number: Double,
        val volume: Int? = null,
        val title: String? = null,
        val createdAt: String,
        val translator: String? = null,
    )
}
