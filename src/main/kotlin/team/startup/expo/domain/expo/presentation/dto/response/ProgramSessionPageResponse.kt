package team.startup.expo.domain.expo.presentation.dto.response

data class ProgramSessionPageResponse(
    val expoId: String,
    val items: List<ProgramDefinition>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val definitionVersion: String,
)
