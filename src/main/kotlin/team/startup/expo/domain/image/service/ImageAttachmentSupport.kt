package team.startup.expo.domain.image.service

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.domain.image.entity.ExpoImage
import team.startup.expo.domain.image.repository.ExpoImageRepository
import team.startup.expo.global.exception.ExpectedException

@Component
class ImageAttachmentSupport(
    private val images: ExpoImageRepository,
) {
    fun validate(
        url: String,
        uploadedBy: String,
        expoId: String,
    ): ExpoImage {
        val image =
            images.findByPublicUrlForUpdate(url)
                ?: throw ExpectedException(HttpStatus.CONFLICT, "업로드된 이미지를 찾을 수 없습니다.")
        if (image.uploadedBy != uploadedBy || image.status != ExpoImage.PENDING ||
            (image.reservedExpoId != null && image.reservedExpoId != expoId)
        ) {
            throw ExpectedException(HttpStatus.CONFLICT, "이미지를 연결할 수 없습니다.")
        }
        return image
    }

    fun release(
        url: String,
        expoId: String,
    ) {
        images.findByPublicUrlForUpdate(url)?.releaseReservation(expoId)
    }
}
