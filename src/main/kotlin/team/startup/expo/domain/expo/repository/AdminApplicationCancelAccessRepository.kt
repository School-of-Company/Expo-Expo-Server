package team.startup.expo.domain.expo.repository

import org.springframework.data.jpa.repository.JpaRepository
import team.startup.expo.domain.expo.entity.AdminApplicationCancelAccess

interface AdminApplicationCancelAccessRepository : JpaRepository<AdminApplicationCancelAccess, Long> {
    fun existsByExpoIdAndAdminId(
        expoId: String,
        adminId: Long,
    ): Boolean
}
