package team.startup.expo.domain.expo.repository

import org.springframework.data.jpa.repository.JpaRepository
import team.startup.expo.domain.expo.entity.ExpoFeatureSetting

interface ExpoFeatureSettingRepository : JpaRepository<ExpoFeatureSetting, Long> {
    fun findAllByExpoId(expoId: String): List<ExpoFeatureSetting>
}
