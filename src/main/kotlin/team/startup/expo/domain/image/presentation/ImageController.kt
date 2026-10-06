package team.startup.expo.domain.image.presentation

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import team.startup.expo.domain.image.presentation.dto.UploadImageResponse
import team.startup.expo.domain.image.service.GetImageService
import team.startup.expo.domain.image.service.UploadImageService
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/image")
class ImageController(
    private val uploadImageService: UploadImageService,
    private val getImageService: GetImageService,
) {
    @Operation(summary = "관리자 이미지 업로드")
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestPart("image") image: MultipartFile,
        authentication: Authentication,
    ): ResponseEntity<UploadImageResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(UploadImageResponse(uploadImageService.execute(image, authentication.name)))

    @Operation(summary = "이미지 조회")
    @GetMapping("/{id}")
    fun getImage(
        @PathVariable id: String,
    ): ResponseEntity<ByteArray> {
        val image = getImageService.execute(id)
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(image.contentType))
            .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
            .body(image.bytes)
    }
}
