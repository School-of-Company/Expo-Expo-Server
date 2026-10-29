package team.startup.expo.domain.image.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.image.entity.ExpoImage
import team.startup.expo.domain.image.repository.ExpoImageRepository
import team.startup.expo.domain.image.service.GetImageService
import team.startup.expo.domain.image.service.ImageResource
import team.startup.expo.domain.image.storage.ImageStorage
import team.startup.expo.global.exception.ExpectedException
import java.io.IOException

@Service
class GetImageServiceImpl(
    private val imageRepository: ExpoImageRepository,
    storages: List<ImageStorage>,
) : GetImageService {
    private val storageByProvider = storages.associateBy { it.provider }

    @Transactional(readOnly = true)
    override fun execute(id: String): ImageResource {
        val image =
            imageRepository.findByIdOrNull(id)?.takeIf { it.status != ExpoImage.DELETING }
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "이미지를 찾을 수 없습니다.")
        val storage =
            storageByProvider[image.storageProvider]
                ?: throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "이미지를 읽을 수 없습니다.")
        val resource =
            try {
                storage.read(image.objectKey)
            } catch (exception: IOException) {
                throw ExpectedException(HttpStatus.SERVICE_UNAVAILABLE, "이미지를 읽을 수 없습니다.")
            }
        return ImageResource(resource, image.contentType)
    }
}
