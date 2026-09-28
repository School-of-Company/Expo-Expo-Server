package team.startup.expo.domain.training.presentation.dto.response

import team.startup.expo.domain.training.entity.Category

data class TrainingProgramResponse(
    val id: Long,
    val title: String,
    val startedAt: String,
    val endedAt: String,
    val category: Category,
)
