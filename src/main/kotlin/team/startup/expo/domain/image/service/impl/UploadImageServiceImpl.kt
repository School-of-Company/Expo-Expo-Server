package team.startup.expo.domain.image.service.impl

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import team.startup.expo.domain.image.entity.ExpoImage
import team.startup.expo.domain.image.repository.ExpoImageRepository
import team.startup.expo.domain.image.service.UploadImageService
import team.startup.expo.domain.image.storage.ImageStorage
import team.startup.expo.global.exception.ExpectedException
import java.io.IOException
import java.util.UUID

@Service
class UploadImageServiceImpl(
    private val imageRepository: ExpoImageRepository,
    private val validator: ImageFileValidator,
    storages: List<ImageStorage>,
    @Value("\${image.public-base-url}") private val publicBaseUrl: String,
    @Value("\${image.storage.active}") activeProvider: String,
) : UploadImageService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val storageByProvider = storages.associateBy { it.provider }
    private val activeStorage =
        requireNotNull(storageByProvider[activeProvider]) { "Image storage provider is unavailable: $activeProvider" }

    override fun execute(
        file: MultipartFile,
        uploadedBy: String,
    ): String {
        val image = validator.validateAndEncode(file)
        val id = UUID.randomUUID().toString()
        val key = "img/$id.${image.extension}"
        val url = "${publicBaseUrl.trimEnd('/')}/image/$id"
        try {
            activeStorage.put(key, image.bytes, image.contentType)
        } catch (exception: IOException) {
            throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "이미지를 저장할 수 없습니다.")
        }
        try {
            imageRepository.saveAndFlush(
                ExpoImage(
                    id = id,
                    storageProvider = activeStorage.provider,
                    objectKey = key,
                    publicUrl = url,
                    contentType = image.contentType,
                    uploadedBy = uploadedBy,
                ),
            )
        } catch (exception: RuntimeException) {
            try {
                activeStorage.delete(key)
            } catch (cleanupFailure: IOException) {
                logger.error("Image file cleanup failed after asset save failure: {}", key, cleanupFailure)
            }
            throw exception
        }
        return url
    }
}
