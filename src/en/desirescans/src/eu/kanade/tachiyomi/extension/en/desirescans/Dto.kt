package eu.kanade.tachiyomi.extension.en.desirescans

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

@Serializable
class SeriesListResponse(
    val data: List<SeriesDto>,
    val meta: MetaDto,
) {
    fun toMangasPage(): MangasPage = MangasPage(
        data.map { it.toSManga() },
        meta.hasMore,
    )
}

@Serializable
class SeriesDto(
    private val slug: String,
    private val title: String,
    private val coverImage: String?,
    private val status: String?,
    private val genres: List<GenreDto> = emptyList(),
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = slug
        this.title = this@SeriesDto.title
        thumbnail_url = coverImage?.let { "https://desirescans.com$it" }
        genre = genres.joinToString { it.genre.slug }
        this@SeriesDto.status?.uppercase()?.let {
            status = when (it) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        } ?: run { status = SManga.UNKNOWN }
    }
}

@Serializable
class GenreDto(
    val genre: GenreInfoDto,
)

@Serializable
class GenreInfoDto(
    val slug: String,
)

@Serializable
class MetaDto(
    val hasMore: Boolean,
)
