package team.startup.expo.domain.image.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import team.startup.expo.domain.image.presentation.dto.UploadImageResponse
import team.startup.expo.domain.image.service.GetImageService
import team.startup.expo.domain.image.service.UploadImageService
import team.startup.expo.global.swagger.ApiError
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/image")
class ImageController(
    private val uploadImageService: UploadImageService,
    private val getImageService: GetImageService,
) {
    @Operation(summary = "관리자 이미지 업로드")
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ApiResponse(responseCode = "201", description = "처리 성공")
    @ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
    @ApiError(status = 413, description = "업로드 요청 크기가 제한을 초과했습니다.")
    @ApiError(status = 415, description = "JPEG 또는 PNG 이미지가 아니거나 multipart/form-data 요청이 아닙니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun upload(
        @RequestPart("image") image: MultipartFile,
        authentication: Authentication,
    ): ResponseEntity<UploadImageResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(UploadImageResponse(uploadImageService.execute(image, authentication.name)))

    @Operation(summary = "이미지 조회")
    @GetMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "처리 성공")
    @ApiResponse(responseCode = "304", description = "이미지가 변경되지 않았습니다.")
    @ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
    @ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
    fun getImage(
        @PathVariable id: String,
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<Resource> {
        val image = getImageService.execute(id)
        // Spring의 GET ETag 비교는 와일드카드를 일치로 처리하지 않는다.
        val wildcardMatch = ifNoneMatch?.trim() == "*"
        return ResponseEntity
            .status(if (wildcardMatch) HttpStatus.NOT_MODIFIED else HttpStatus.OK)
            .contentType(MediaType.parseMediaType(image.contentType))
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
            .eTag("\"$id\"")
            .body(if (wildcardMatch) null else image.resource)
    }
}
