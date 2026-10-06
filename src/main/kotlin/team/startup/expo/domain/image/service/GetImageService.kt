package team.startup.expo.domain.image.service

interface GetImageService {
    fun execute(id: String): ImageBytes
}

data class ImageBytes(
    val bytes: ByteArray,
    val contentType: String,
)
