package team.startup.expo.domain.standard.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.standard.entity.StandardProgram

interface StandardProgramRepository : JpaRepository<StandardProgram, Long> {
    @Query("select program.expo.id from StandardProgram program where program.id = :id")
    fun findExpoIdById(id: Long): String?

    fun findByExpo(expo: Expo): List<StandardProgram>

    fun findAllByIdInAndExpoId(
        ids: List<Long>,
        expoId: String,
    ): List<StandardProgram>

    fun findByExpoIdOrderByIdAsc(expoId: String): List<StandardProgram>

    fun findByIdAndExpoId(
        id: Long,
        expoId: String,
    ): StandardProgram?

    fun findByGroupId(groupId: java.util.UUID): List<StandardProgram>

    @Query(
        "SELECT md5(COALESCE(string_agg(program_type || ':' || id || ':' || revision, ',' ORDER BY program_type, id), '')) " +
            "FROM (" + SOURCE + ") programs",
        nativeQuery = true,
    )
    fun sessionVersion(expoId: String): String

    @Query("SELECT count(*) FROM (" + SOURCE + ") programs WHERE " + FILTER, nativeQuery = true)
    fun sessionCount(
        expoId: String,
        programType: String?,
        groupId: java.util.UUID?,
        programId: Long?,
    ): Long

    @Query(
        "WITH page AS MATERIALIZED (SELECT program_type,id FROM (" + SOURCE + ") programs WHERE " + FILTER +
            " ORDER BY program_type,id LIMIT :size OFFSET :offset) " +
            """SELECT page.program_type AS programType,
                CASE WHEN page.program_type='STANDARD' THEN to_jsonb(s) ELSE to_jsonb(t) END::text AS payload
            FROM page LEFT JOIN tb_standard_program s ON page.program_type='STANDARD' AND s.id=page.id
            LEFT JOIN tb_training_program t ON page.program_type='TRAINING' AND t.id=page.id
            ORDER BY page.program_type,page.id""",
        nativeQuery = true,
    )
    fun sessionPage(
        expoId: String,
        programType: String?,
        groupId: java.util.UUID?,
        programId: Long?,
        size: Int,
        offset: Long,
    ): List<SessionRow>

    interface SessionRow {
        val programType: String
        val payload: String
    }

    companion object {
        private const val SOURCE =
            "SELECT 'STANDARD' AS program_type,id,group_id,revision FROM tb_standard_program WHERE expo_id=:expoId " +
                "UNION ALL SELECT 'TRAINING',id,group_id,revision FROM tb_training_program WHERE expo_id=:expoId"
        private const val FILTER =
            "(CAST(:programType AS text) IS NULL OR program_type=:programType) AND " +
                "(CAST(:groupId AS uuid) IS NULL OR group_id=:groupId) AND (CAST(:programId AS bigint) IS NULL OR id=:programId)"
    }
}
