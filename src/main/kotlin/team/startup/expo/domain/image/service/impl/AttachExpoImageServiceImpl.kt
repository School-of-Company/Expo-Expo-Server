package team.startup.expo.domain.image.service.impl

import org.springframework.stereotype.Service
import team.startup.expo.domain.image.entity.ExpoImage
import team.startup.expo.domain.image.repository.ExpoImageRepository
import team.startup.expo.domain.image.service.AttachExpoImageService
import team.startup.expo.domain.image.service.ImageAttachmentSupport

@Service
class AttachExpoImageServiceImpl(
    private val imageRepository: ExpoImageRepository,
    private val attachment: ImageAttachmentSupport,
) : AttachExpoImageService {
    override fun execute(
        url: String,
        uploadedBy: String,
        expoId: String,
        previousUrl: String?,
    ) {
        if (url == previousUrl) return
        val image = attachment.validate(url, uploadedBy, expoId)
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
