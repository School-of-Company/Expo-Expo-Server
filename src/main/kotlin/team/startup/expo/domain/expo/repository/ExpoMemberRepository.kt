package team.startup.expo.domain.expo.repository

import org.springframework.data.jpa.repository.JpaRepository
import team.startup.expo.domain.expo.entity.ExpoMember
import team.startup.expo.domain.expo.entity.ExpoMemberRole

interface ExpoMemberRepository : JpaRepository<ExpoMember, Long> {
    fun findByExpoIdAndAdminId(
        expoId: String,
        adminId: Long,
    ): ExpoMember?

    fun findAllByExpoIdOrderByAdminId(expoId: String): List<ExpoMember>

    fun findAllByAdminId(adminId: Long): List<ExpoMember>

    fun countByExpoIdAndRole(
        expoId: String,
        role: ExpoMemberRole,
    ): Long
}
