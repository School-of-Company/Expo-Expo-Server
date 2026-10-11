package team.startup.expo.domain.expo.presentation.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Pattern

data class ExpoPageRequest(
    @field:Pattern(regexp = "[0-9]+")
    @field:DecimalMin("0")
    @field:DecimalMax("2147483647")
    @field:Schema(description = "0부터 시작하는 페이지 번호", example = "0")
    val page: String? = null,
    @field:Pattern(regexp = "[0-9]+")
    @field:DecimalMin("1")
    @field:DecimalMax("100")
    @field:Schema(description = "페이지 크기. 1~100, 기본 20", example = "20")
    val size: String? = null,
) {
    val isPaged: Boolean get() = page != null || size != null

    val pageNumber: Int get() = page?.toInt() ?: 0

    val pageSize: Int get() = size?.toInt() ?: 20
}
