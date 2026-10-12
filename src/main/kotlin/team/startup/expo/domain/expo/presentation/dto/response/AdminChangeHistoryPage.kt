package team.startup.expo.domain.expo.presentation.dto.response

import java.time.Instant

data class AdminChangeHistoryItem(
    val id: Long,
    val type: String,
    val adminId: Long?,
    val occurredAt: Instant,
    val targetType: String,
    val targetId: Long,
    val summary: String,
)

data class AdminChangeHistoryPage(
    val expoId: String,
    val items: List<AdminChangeHistoryItem>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)
