package team.startup.expo.domain.image.service

import org.springframework.core.io.Resource

interface GetImageService {
    fun execute(id: String): ImageResource
}

data class ImageResource(
    val resource: Resource,
    val contentType: String,
)
