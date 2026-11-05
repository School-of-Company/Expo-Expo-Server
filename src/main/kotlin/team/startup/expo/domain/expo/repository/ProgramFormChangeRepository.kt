package team.startup.expo.domain.expo.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.entity.ProgramFormChange
import team.startup.expo.domain.expo.entity.ProgramFormChangeId
import team.startup.expo.domain.expo.presentation.dto.response.ProgramFormUsageResponse
import java.time.Instant
import java.util.UUID

interface ProgramFormChangeRepository : JpaRepository<ProgramFormChange, ProgramFormChangeId> {
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("SELECT id FROM tb_program_form_guard WHERE id=1 FOR UPDATE", nativeQuery = true)
    fun lock(): Int

    @Query(
        POLICIES + """SELECT count(*) > 0 AS referenced,
            min(LEAST((policy->>'applicationStartedAt')::timestamptz, (policy->>'onsiteStartedAt')::timestamptz)) AS started,
            max(GREATEST((policy->>'applicationEndedAt')::timestamptz, (policy->>'onsiteEndedAt')::timestamptz)) AS ended
            FROM policies WHERE policy->>'formId' = :formId""",
        nativeQuery = true,
    )
    fun findUsage(formId: String): Usage

    fun usage(formId: UUID): ProgramFormUsageResponse {
        val row = findUsage(formId.toString())
        return ProgramFormUsageResponse(formId, row.referenced, row.started, row.ended)
    }

    interface Usage {
        val referenced: Boolean
        val started: Instant?
        val ended: Instant?
    }

    @Query(
        POLICIES + "SELECT EXISTS(SELECT 1 FROM policies WHERE policy->>'formId'=:formId AND policy->>'guardianPolicy' IS NOT NULL)",
        nativeQuery = true,
    )
    fun guardianReferencedById(formId: String): Boolean

    fun guardianReferenced(formId: UUID): Boolean = guardianReferencedById(formId.toString())

    @Query("select change from ProgramFormChange change where change.formId=:formId and change.changeId=:changeId")
    fun find(
        formId: UUID,
        changeId: UUID,
    ): ProgramFormChange?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM tb_program_form_change WHERE form_id=:formId AND NOT released AND expires_at > clock_timestamp())",
        nativeQuery = true,
    )
    fun blocked(formId: UUID): Boolean

    @Query(
        """SELECT CASE WHEN bool_or(NOT released AND expires_at > clock_timestamp()) THEN NULL ELSE count(*) END
            FROM tb_program_form_change WHERE form_id=:formId""",
        nativeQuery = true,
    )
    fun version(formId: UUID): Long?

    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "UPDATE tb_program_form_change SET released=TRUE WHERE form_id=:formId AND NOT released AND expires_at<=clock_timestamp()",
        nativeQuery = true,
    )
    fun releaseExpired(formId: UUID): Int

    @Query("SELECT clock_timestamp()", nativeQuery = true)
    fun now(): Instant

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """INSERT INTO tb_program_form_change(form_id,change_id,request_json,response_json,expires_at)
            VALUES (:formId,:changeId,:request,:response,:expiresAt)""",
        nativeQuery = true,
    )
    fun reserve(
        formId: UUID,
        changeId: UUID,
        request: String,
        response: String,
        expiresAt: Instant,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """INSERT INTO tb_program_form_change(form_id,change_id,released) VALUES (:formId,:changeId,TRUE)
            ON CONFLICT (form_id,change_id) DO UPDATE SET released=TRUE""",
        nativeQuery = true,
    )
    fun release(
        formId: UUID,
        changeId: UUID,
    ): Int

    companion object {
        private const val POLICIES =
            """WITH policies AS (
                SELECT policy_json::jsonb AS policy FROM tb_standard_program WHERE policy_json IS NOT NULL
                UNION ALL
                SELECT policy_json::jsonb FROM tb_training_program WHERE policy_json IS NOT NULL
                UNION ALL
                SELECT mutation->'definition'->'policy'
                FROM tb_program_change, jsonb_array_elements(payload_json::jsonb->'mutations') mutation
            ) """
    }
}
