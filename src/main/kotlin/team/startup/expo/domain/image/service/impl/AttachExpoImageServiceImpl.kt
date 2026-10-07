package team.startup.expo.domain.image.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.image.entity.ExpoImage
import team.startup.expo.domain.image.repository.ExpoImageRepository
import team.startup.expo.domain.image.service.AttachExpoImageService
import team.startup.expo.global.exception.ExpectedException

@Service
class AttachExpoImageServiceImpl(
    private val imageRepository: ExpoImageRepository,
) : AttachExpoImageService {
    override fun execute(
        url: String,
        uploadedBy: String,
        expoId: String,
        previousUrl: String?,
    ) {
        if (url == previousUrl) return
        val image =
            imageRepository.findByPublicUrlForUpdate(url)
                ?: throw ExpectedException(HttpStatus.CONFLICT, "업로드된 이미지를 찾을 수 없습니다.")
        if (image.uploadedBy != uploadedBy || image.status != ExpoImage.PENDING) {
            throw ExpectedException(HttpStatus.CONFLICT, "이미지를 연결할 수 없습니다.")
        }
        image.attach(expoId)
        previousUrl?.let { oldUrl ->
            imageRepository
                .findByPublicUrlForUpdate(oldUrl)
                ?.takeIf {
                    it.status == ExpoImage.ATTACHED && it.expoId == expoId
                }?.detach()
        }
    }
}
