package team.startup.expo.domain.expo.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPolicyRequest
import team.startup.expo.domain.expo.presentation.dto.request.ProgramSessionPageRequest
import team.startup.expo.domain.expo.presentation.dto.response.ProgramDefinition
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.training.entity.Category
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Repository
class ProgramSessionQueryRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun version(expoId: String): String =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT md5(COALESCE(string_agg(program_type || ':' || definition::text, ',' ORDER BY program_type, id), '')) FROM ($SOURCE) programs",
                String::class.java,
                expoId,
                expoId,
            ),
        )

    fun count(
        expoId: String,
        request: ProgramSessionPageRequest,
    ): Long =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM ($SOURCE) programs WHERE $FILTER",
                Long::class.java,
                *parameters(expoId, request),
            ),
        )

    fun page(
        expoId: String,
        request: ProgramSessionPageRequest,
    ): List<ProgramDefinition> =
        jdbc.query(
            "SELECT program_type, definition::text FROM ($SOURCE) programs WHERE $FILTER ORDER BY program_type, id LIMIT ? OFFSET ?",
            { row, _ ->
                val node = mapper.readTree(row.getString(2))
                val policy = node.get("policy_json")?.takeUnless { it.isNull }?.asString()
                ProgramDefinition(
                    expoId = expoId,
                    programType = ProgramType.valueOf(row.getString(1)),
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
                )
            },
            *parameters(expoId, request),
            request.size,
            request.page.toLong() * request.size,
        )

    private fun parameters(
        expoId: String,
        request: ProgramSessionPageRequest,
    ): Array<Any?> =
        arrayOf(
            expoId,
            expoId,
            request.programType?.name,
            request.programType?.name,
            request.groupId?.toString(),
            request.groupId?.toString(),
            request.programId,
            request.programId,
        )

    private companion object {
        const val SOURCE =
            "SELECT 'STANDARD' AS program_type, id, to_jsonb(p) AS definition FROM tb_standard_program p WHERE expo_id = ? " +
                "UNION ALL SELECT 'TRAINING', id, to_jsonb(p) FROM tb_training_program p WHERE expo_id = ?"
        const val FILTER =
            "(?::text IS NULL OR program_type = ?) AND " +
                "(?::text IS NULL OR definition->>'group_id' = ?) AND (?::bigint IS NULL OR id = ?)"
    }
}
