package team.startup.expo.domain.image

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpStatus
import org.springframework.test.util.ReflectionTestUtils
import team.startup.expo.domain.image.entity.ExpoImage
import team.startup.expo.domain.image.repository.ExpoImageRepository
import team.startup.expo.domain.image.service.impl.GetImageServiceImpl
import team.startup.expo.domain.image.storage.impl.LocalImageStorage
import team.startup.expo.global.exception.ExpectedException
import java.nio.file.Path
import java.util.Optional
import java.util.UUID

class GetImageServiceImplTests {
    @TempDir
    lateinit var directory: Path

    private val repository = mock(ExpoImageRepository::class.java)
    private val id = UUID.randomUUID().toString()
    private val key = "img/$id.png"

    @Test
    fun `이미지는 전체 바이트 대신 파일 리소스로 반환하고 요청할 때 읽는다`() {
        val storage = LocalImageStorage(directory.toString())
        val bytes = byteArrayOf(1, 2, 3)
        storage.put(key, bytes, "image/png")
        givenImage()

        val response = GetImageServiceImpl(repository, listOf(storage)).execute(id)

        response.contentType shouldBe "image/png"
        (response.resource is FileSystemResource) shouldBe true
        response.resource.contentLength() shouldBe bytes.size.toLong()
        response.resource.inputStream.use { it.readBytes().toList() } shouldBe bytes.toList()
        storage.delete(key)
        assertThrows(java.io.IOException::class.java) { response.resource.inputStream }
    }

    @Test
    fun `없는 이미지와 삭제 중인 이미지는 404다`() {
        val service = GetImageServiceImpl(repository, emptyList())
        `when`(repository.findById(id)).thenReturn(Optional.empty())
        assertThrows(ExpectedException::class.java) { service.execute(id) }.status shouldBe HttpStatus.NOT_FOUND

        val image = givenImage()
        ReflectionTestUtils.setField(image, "status", ExpoImage.DELETING)
        assertThrows(ExpectedException::class.java) { service.execute(id) }.status shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `저장소가 없으면 503이다`() {
        givenImage()

        val exception = assertThrows(ExpectedException::class.java) { GetImageServiceImpl(repository, emptyList()).execute(id) }

        exception.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
        exception.message shouldBe "이미지를 읽을 수 없습니다."
    }

    @Test
    fun `파일이 없으면 리소스 응답 전에 503이다`() {
        givenImage()
        val service = GetImageServiceImpl(repository, listOf(LocalImageStorage(directory.toString())))

        val exception = assertThrows(ExpectedException::class.java) { service.execute(id) }

        exception.status shouldBe HttpStatus.SERVICE_UNAVAILABLE
        exception.message shouldBe "이미지를 읽을 수 없습니다."
    }

    private fun givenImage(): ExpoImage {
        val image = ExpoImage(id, "local", key, "/image/$id", "image/png", "1")
        `when`(repository.findById(id)).thenReturn(Optional.of(image))
        return image
    }
}
