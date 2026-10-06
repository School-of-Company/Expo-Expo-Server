package team.startup.expo.domain.image.service

interface AttachExpoImageService {
    fun execute(
        url: String,
        uploadedBy: String,
        expoId: String,
        previousUrl: String? = null,
    )
}
