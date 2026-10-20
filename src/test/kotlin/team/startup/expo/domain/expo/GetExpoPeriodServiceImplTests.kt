package team.startup.expo.domain.expo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.impl.GetExpoPeriodServiceImpl
import team.startup.expo.global.exception.ExpectedException
import java.util.Optional

class GetExpoPeriodServiceImplTests {
    private val repository = mock(ExpoRepository::class.java)
    private val service = GetExpoPeriodServiceImpl(repository)

    @Test
    fun `응답에 박람회 장소가 포함된다`() {
        val expo = Expo("expo-1", "제목", "설명", "2030-01-01", "2030-01-02", "광주 컨벤션센터", null, "1", "1", 0, 0)
        `when`(repository.findById("expo-1")).thenReturn(Optional.of(expo))

        val response = service.execute("expo-1")

        response.location shouldBe "광주 컨벤션센터"
        response.title shouldBe "제목"
        response.startedDay shouldBe "2030-01-01"
        response.finishedDay shouldBe "2030-01-02"
    }

    @Test
    fun `없는 박람회는 404다`() {
        `when`(repository.findById("none")).thenReturn(Optional.empty())

        val e = assertThrows(ExpectedException::class.java) { service.execute("none") }

        e.status shouldBe HttpStatus.NOT_FOUND
    }
}
