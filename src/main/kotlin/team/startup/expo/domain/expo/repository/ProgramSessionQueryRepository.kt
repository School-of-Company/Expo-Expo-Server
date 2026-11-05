package team.startup.expo.domain.expo.repository

import org.springframework.stereotype.Repository
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.expo.presentation.dto.request.ProgramSessionPageRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramDefinition
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.entity.Category
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Repository
class ProgramSessionQueryRepository(
    private val programs: StandardProgramRepository,
    private val mapper: ObjectMapper,
) {
    fun version(expoId: String): String = programs.sessionVersion(expoId)

    fun count(
        expoId: String,
        request: ProgramSessionPageRequest,
    ): Long = programs.sessionCount(expoId, request.programType?.name, request.groupId, request.programId)

    fun page(
        expoId: String,
        request: ProgramSessionPageRequest,
    ): List<ProgramDefinition> =
        programs
            .sessionPage(
                expoId,
                request.programType?.name,
                request.groupId,
                request.programId,
                request.size,
                request.page.toLong() * request.size,
            ).map { row ->
                val node = mapper.readTree(row.payload)
                val policy = node.get("policy_json")?.takeUnless { it.isNull }?.asString()
                ProgramDefinition(
                    expoId = expoId,
                    programType = ProgramType.valueOf(row.programType),
                    id = node.get("id").asLong(),
                    title = node.get("title").asString(),
                    startedAt = node.get("started_at").asString(),
                    endedAt = node.get("ended_at").asString(),
                    category =
                        node
                            .get("category")
                            ?.takeUnless { it.isNull }
                            ?.asString()
                            ?.let(Category::valueOf),
                    groupId = UUID.fromString(node.get("group_id").asString()),
                    policy = policy?.let { mapper.readValue(it, ProgramPolicyRequest::class.java) },
                    revision = node.get("revision").asLong(),
                    place = node.get("place")?.takeUnless { it.isNull }?.asString(),
                )
            }
}
