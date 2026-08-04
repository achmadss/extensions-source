package eu.kanade.tachiyomi.extension.en.omanga

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
class MangaListDto(
    @SerialName("initialItems")
    private val initialItems: List<MangaDto>,
    @SerialName("initialHasMore")
    private val initialHasMore: Boolean,
) {
    fun toMangasPage() = MangasPage(
        initialItems.map { it.toSManga() },
        initialHasMore,
    )
}

@Serializable
class MangaDto(
    @SerialName("slug")
    private val slug: String,
    @SerialName("title")
    private val title: String,
    @SerialName("poster")
    private val poster: String? = null,
    @SerialName("genres")
    private val genres: List<String> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        this.title = this@MangaDto.title
        thumbnail_url = poster
        genre = genres.joinToString()
    }
}

@Serializable
class MangaDetailDto(
    @SerialName("slug")
    private val slug: String,
    @SerialName("title")
    private val title: String,
    @SerialName("author")
    private val author: String? = null,
    @SerialName("artist")
    private val artist: String? = null,
    @SerialName("status")
    private val status: String? = null,
    @SerialName("description")
    private val description: String? = null,
    @SerialName("poster")
    private val poster: String? = null,
    @SerialName("genres")
    private val genres: List<String> = emptyList(),
    @SerialName("chapters")
    private val chapters: List<ChapterDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        this.title = this@MangaDetailDto.title
        this.author = this@MangaDetailDto.author
        this.artist = this@MangaDetailDto.artist
        this.description = this@MangaDetailDto.description
        this.genre = this@MangaDetailDto.genres.joinToString()
        this.status = this@MangaDetailDto.status.toSMangaStatus()
        this.thumbnail_url = this@MangaDetailDto.poster
    }

    fun toChapters(): List<SChapter> = chapters.map { it.toSChapter(slug) }
}

@Serializable
class ChapterDto(
    @SerialName("number")
    private val number: Double,
    @SerialName("title")
    private val title: String? = null,
    @SerialName("createdAt")
    private val createdAt: String? = null,
    @SerialName("translator")
    private val translator: String? = null,
) {
    fun toSChapter(slug: String) = SChapter.create().apply {
        val chapterNumber = number.toString().removeSuffix(".0")
        url = "/manga/$slug/chapter/$chapterNumber"
        name = buildString {
            append("Chapter ")
            append(chapterNumber)
            title?.takeIf { it.isNotEmpty() }?.let {
                append(" - ")
                append(it)
            }
            translator?.takeIf { it.isNotEmpty() }?.let {
                append(" [")
                append(it)
                append("]")
            }
        }
        chapter_number = number.toFloat()
        date_upload = Instant.tryParse(createdAt)
    }
}

@Serializable
class ChapterPageDto(
    @SerialName("chapter")
    private val chapter: ChapterPagesDto,
) {
    fun toPageList(): List<Page> = chapter.toPageList()
}

@Serializable
class ChapterPagesDto(
    @SerialName("pages")
    private val pages: List<String>,
) {
    fun toPageList(): List<Page> = pages.mapIndexed { index, imageUrl ->
        Page(index, imageUrl = imageUrl)
    }
}

private fun String?.toSMangaStatus(): Int = when (this?.lowercase()) {
    "ongoing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus" -> SManga.ON_HIATUS
    "dropped", "cancelled", "canceled" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}
