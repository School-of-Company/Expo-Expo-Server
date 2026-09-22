package team.startup.expo.domain.standard.repository

import org.springframework.data.jpa.repository.JpaRepository
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.standard.entity.StandardProgram

interface StandardProgramRepository : JpaRepository<StandardProgram, Long> {
    fun deleteByExpo(expo: Expo)

    fun findByExpo(expo: Expo): List<StandardProgram>

    fun findByIdAndExpoId(
        id: Long,
        expoId: String,
    ): StandardProgram?
}
