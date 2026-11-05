package team.startup.expo.domain.expo.repository

import org.springframework.data.jpa.repository.JpaRepository
import team.startup.expo.domain.expo.entity.AdminChangeHistory
import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeTargetType
import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeType

interface AdminChangeHistoryRepository :
    JpaRepository<AdminChangeHistory, Long>,
    AdminChangeHistoryQueries {
    fun record(
        expoId: String,
        type: AdminChangeType,
        targetType: AdminChangeTargetType,
        targetId: Long,
        summary: String,
        adminId: Long?,
    ) {
        save(AdminChangeHistory(expoId, type, targetType, targetId, summary, adminId))
    }
}
