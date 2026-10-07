package team.startup.expo.domain.expo.presentation.dto.response

data class ExpoPageResponse(
    val content: List<ExpoSummaryResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
)
