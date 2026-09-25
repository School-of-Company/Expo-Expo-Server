package team.startup.expo.domain.expo.presentation.dto.response

data class ExpoDetailResponse(
    val title: String,
    val description: String,
    val startedDay: String,
    val finishedDay: String,
    val location: String,
    val coverImage: String?,
    val x: String,
    val y: String,
)
