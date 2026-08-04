package eu.kanade.tachiyomi.extension.en.desirescans

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
data class SeriesListResponse(
    val data: List<SeriesDto>,
    val meta: MetaDto,
)

@Serializable
data class SeriesDto(
    val id: String,
    val slug: String,
    val urlSlug: String,
    val title: String,
    val coverImage: String?,
    val type: String?,
    val status: String?,
    val genres: List<GenreDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = slug
        title = this@SeriesDto.title
        thumbnail_url = coverImage?.let { "https://desirescans.com$it" }
        genre = genres.joinToString { it.genre.slug }
        status = when (this@SeriesDto.status?.uppercase()) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
data class GenreDto(
    val genre: GenreInfoDto,
)

@Serializable
data class GenreInfoDto(
    val slug: String,
)

@Serializable
data class ChapterDto(
    val id: String,
    val number: Int,
    val title: String?,
    val createdAt: String,
) {
    fun toSChapter(seriesSlug: String): SChapter = SChapter.create().apply {
        url = "$seriesSlug/chapter/$number"
        name = title ?: "Chapter $number"
        date_upload = parseDate(createdAt)
    }

    private fun parseDate(dateString: String): Long = try {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
        format.parse(dateString)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }
}

@Serializable
data class MetaDto(
    val total: Int,
    val page: Int,
    val limit: Int,
    val totalPages: Int,
    val hasMore: Boolean,
)
