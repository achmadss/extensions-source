package eu.kanade.tachiyomi.extension.en.mangabuddy1

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SeriesListResponse(
    val comics: List<ComicItem>,
    val pagination: Pagination,
)

@Serializable
data class SearchResponse(
    val comics: List<ComicItem>,
    val pagination: Pagination,
)

@Serializable
data class ComicItem(
    val title: String,
    val slug: String,
    val image: String,
    val status: String,
    val kind: String,
    val score: Int,
    val comic_id: Long,
    @SerialName("is_18") val is18: Boolean? = null,
) {
    fun toSManga() = eu.kanade.tachiyomi.source.model.SManga.create().apply {
        url = "/series/$slug"
        title = this@ComicItem.title
        thumbnail_url = image
        status = when (this@ComicItem.status.lowercase()) {
            "ongoing" -> eu.kanade.tachiyomi.source.model.SManga.ONGOING
            "completed" -> eu.kanade.tachiyomi.source.model.SManga.COMPLETED
            else -> eu.kanade.tachiyomi.source.model.SManga.UNKNOWN
        }
        genre = kind
    }
}

@Serializable
data class Pagination(
    val current_page: Int,
    val per_page: Int,
    val total: Int? = null,
    val total_items: Int? = null,
    val total_pages: Int,
    val has_next_page: Boolean,
    val has_prev_page: Boolean,
    val next_page: Int? = null,
    val prev_page: Int? = null,
)

@Serializable
data class SeriesDetailResponse(
    val comic: ComicDetail,
    val chapters: List<ChapterItem>,
)

@Serializable
data class ComicDetail(
    val title: String,
    val slug: String,
    val cover: String,
    val status: String,
    val kind: String,
    val description: String,
    val author: String,
    val artist: String,
    val release_year: String,
    val rating_value: Float,
    val view: Long,
)

@Serializable
data class ChapterItem(
    val number: Float,
    val name: String,
    val url: String,
    val images_count: Int,
    val is_new: Boolean,
    val time: String?,
) {
    fun toSChapter() = eu.kanade.tachiyomi.source.model.SChapter.create().apply {
        url = this@ChapterItem.url.substringAfter("co.uk")
        name = this@ChapterItem.name
        chapter_number = this@ChapterItem.number
        date_upload = 0L
    }
}
