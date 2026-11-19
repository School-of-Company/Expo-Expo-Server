package team.startup.expo.domain.expo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.repository.ExpoFeatureSettingRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.ExpoFeatureCatalog
import team.startup.expo.domain.expo.service.ExpoFeatureSupport
import team.startup.expo.domain.expo.service.impl.GetExpoPeriodServiceImpl
import team.startup.expo.global.exception.ExpectedException
import java.util.Optional

class GetExpoPeriodServiceImplTests {
    private val repository = mock(ExpoRepository::class.java)
    private val service = GetExpoPeriodServiceImpl(repository, ExpoFeatureSupport(mock(ExpoFeatureSettingRepository::class.java), false))

    @Test
    fun `응답에 박람회 장소가 포함된다`() {
        val expo = Expo("expo-1", "제목", "설명", "2030-01-01", "2030-01-02", "광주 컨벤션센터", null, "1", "1", 0, 0)
        `when`(repository.findById("expo-1")).thenReturn(Optional.of(expo))

        val response = service.execute("expo-1")

        response.location shouldBe "광주 컨벤션센터"
        response.title shouldBe "제목"
        response.startedDay shouldBe "2030-01-01"
        response.finishedDay shouldBe "2030-01-02"
        // 설정 행이 없는 기존 박람회는 종전처럼 모든 기능이 실효 ON이다.
        response.features.map { it.key } shouldBe ExpoFeatureCatalog.definitions.map { it.key }
        response.features.all { it.configuredEnabled == true && it.effectiveEnabled } shouldBe true
        response.featureRevision shouldBe 0L
    }

    @Test
    fun `없는 박람회는 404다`() {
        `when`(repository.findById("none")).thenReturn(Optional.empty())

        val e = assertThrows(ExpectedException::class.java) { service.execute("none") }

        e.status shouldBe HttpStatus.NOT_FOUND
    }
}
