package team.startup.expo.domain.expo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

// 박람회별 기능 선택값. 키 허용 목록과 의존 관계는 ExpoFeatureCatalog가 가진다.
@Entity
@Table(name = "tb_expo_feature")
class ExpoFeatureSetting(
    @field:Column(nullable = false, length = 36)
    val expoId: String,
    @field:Column(name = "feature_key", nullable = false, length = 64)
    val featureKey: String,
    @field:Column(nullable = false)
    var enabled: Boolean,
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
)
