package team.startup.expo.domain.image.service

import org.springframework.web.multipart.MultipartFile

interface UploadImageService {
    fun execute(
        file: MultipartFile,
        uploadedBy: String,
    ): String
}
