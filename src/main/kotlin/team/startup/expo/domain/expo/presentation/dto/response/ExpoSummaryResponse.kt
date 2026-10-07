package team.startup.expo.domain.expo.presentation.dto.response

data class ExpoSummaryResponse(
    val id: String,
    val title: String,
    val description: String,
    val startedDay: String,
    val finishedDay: String,
    val coverImage: String?,
)
