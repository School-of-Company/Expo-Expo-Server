package team.startup.expo.domain.expo.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.ProgramFormUsageResponse
import java.time.Instant
import java.util.UUID

@Repository
class ProgramFormChangeRepository(
    private val jdbc: JdbcTemplate,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun lock() {
        // One DB row gives all batches the same lock order, including cross-Expo form sharing.
        jdbc.queryForObject("SELECT id FROM tb_program_form_guard WHERE id=1 FOR UPDATE", Int::class.java)
    }

    fun usage(formId: UUID): ProgramFormUsageResponse =
        jdbc.queryForObject(
            """
            WITH policies AS (
                SELECT policy_json::jsonb AS policy FROM tb_standard_program WHERE policy_json IS NOT NULL
                UNION ALL
                SELECT policy_json::jsonb FROM tb_training_program WHERE policy_json IS NOT NULL
                UNION ALL
                SELECT mutation->'definition'->'policy'
                FROM tb_program_change, jsonb_array_elements(payload_json::jsonb->'mutations') mutation
            )
            SELECT count(*) > 0 AS referenced,
                   min((policy->>'applicationStartedAt')::timestamptz) AS started,
                   max((policy->>'applicationEndedAt')::timestamptz) AS ended
            FROM policies WHERE policy->>'formId' = ?
            """.trimIndent(),
            { row, _ ->
                ProgramFormUsageResponse(
                    formId,
                    row.getBoolean("referenced"),
                    row.getTimestamp("started")?.toInstant(),
                    row.getTimestamp("ended")?.toInstant(),
                )
            },
            formId.toString(),
        )

    fun guardianReferenced(formId: UUID): Boolean =
        jdbc.queryForObject(
            """
            WITH policies AS (
                SELECT policy_json::jsonb AS policy FROM tb_standard_program WHERE policy_json IS NOT NULL
                UNION ALL
                SELECT policy_json::jsonb FROM tb_training_program WHERE policy_json IS NOT NULL
                UNION ALL
                SELECT mutation->'definition'->'policy'
                FROM tb_program_change, jsonb_array_elements(payload_json::jsonb->'mutations') mutation
            )
            SELECT EXISTS(
                SELECT 1 FROM policies
                WHERE policy->>'formId' = ? AND policy->>'guardianPolicy' IS NOT NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            formId.toString(),
        )!!

    fun find(
        formId: UUID,
        changeId: UUID,
    ): Receipt? =
        jdbc
            .query(
                "SELECT * FROM tb_program_form_change WHERE form_id=? AND change_id=?",
                {
                    row,
                    _,
                    ->
                    Receipt(
                        row.getString("request_json"),
                        row.getString("response_json"),
                        row.getTimestamp("expires_at")?.toInstant(),
                        row.getBoolean("released"),
                    )
                },
                formId,
                changeId,
            ).singleOrNull()

    fun blocked(formId: UUID): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tb_program_form_change WHERE form_id=? AND NOT released)",
            Boolean::class.java,
            formId,
        )!!

    fun now(): Instant = jdbc.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() })!!

    fun reserve(
        formId: UUID,
        changeId: UUID,
        request: String,
        response: String,
        expiresAt: Instant,
    ) {
        jdbc.update(
            "INSERT INTO tb_program_form_change(form_id,change_id,request_json,response_json,expires_at) VALUES (?,?,?,?,?)",
            formId,
            changeId,
            request,
            response,
            java.sql.Timestamp.from(expiresAt),
        )
    }

    fun release(
        formId: UUID,
        changeId: UUID,
    ) {
        jdbc.update(
            """
            INSERT INTO tb_program_form_change(form_id,change_id,released) VALUES (?,?,TRUE)
            ON CONFLICT (form_id,change_id) DO UPDATE SET released=TRUE
            """.trimIndent(),
            formId,
            changeId,
        )
    }

    data class Receipt(
        val request: String?,
        val response: String?,
        val expiresAt: Instant?,
        val released: Boolean,
    )
}
