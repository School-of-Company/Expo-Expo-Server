package team.startup.expo.persistence

import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

class ProgramCapacityMigrationTests {
    @Test
    fun `기존 단일 정원과 채널 정책을 보존하며 V37로 업그레이드한다`() {
        PostgreSQLContainer("postgres:17-alpine").use { database ->
            database.start()

            fun flyway(target: String? = null): Flyway =
                Flyway
                    .configure()
                    .dataSource(database.jdbcUrl, database.username, database.password)
                    .also { if (target != null) it.target(target) }
                    .load()
            flyway("36").migrate()
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                connection.createStatement().use { sql ->
                    listOf("standard", "training").forEach { kind ->
                        val category = if (kind == "training") ",category" else ""
                        val categoryValue = if (kind == "training") ",'ESSENTIAL'" else ""
                        (1..4).forEach { id ->
                            sql.execute(
                                "INSERT INTO tb_${kind}_program(id,title,started_at,ended_at$category) VALUES ($id,'기존 회차','2026-10-31T09:00','2026-10-31T10:00'$categoryValue)",
                            )
                        }
                        val base =
                            """
                            jsonb_build_object(
                            'capacity',10,'waitingCapacity',2,'groupId',group_id,
                            'allowedOccupations',jsonb_build_array('GENERAL'),
                            'applicationStartedAt','2026-10-01T09:00+09:00',
                            'applicationEndedAt','2026-10-30T09:00+09:00',
                            'cancelEndedAt','2026-10-30T09:00+09:00',
                            'promotionEndedAt','2026-10-30T09:00+09:00',
                            'formId','00000000-0000-0000-0000-000000000001','closed',false)
                            """.trimIndent()
                        sql.execute("UPDATE tb_${kind}_program SET policy_json=($base)::text WHERE id=1")
                        sql.execute(
                            "UPDATE tb_${kind}_program SET policy_json=($base || '{\"channels\":[{\"name\":\"일반\",\"capacity\":10,\"waitingCapacity\":2,\"allowedOccupations\":[\"GENERAL\"]}]}'::jsonb)::text WHERE id=2",
                        )
                        sql.execute(
                            "UPDATE tb_${kind}_program SET policy_json=($base || '{\"preRegistrationCapacity\":7,\"onsiteCapacity\":3}'::jsonb)::text WHERE id=3",
                        )
                    }
                    sql.execute(
                        "CREATE TABLE policies_before AS SELECT 'STANDARD' AS kind,id,policy_json FROM tb_standard_program UNION ALL SELECT 'TRAINING',id,policy_json FROM tb_training_program",
                    )
                    flyway().migrate()
                    listOf("standard", "training").forEach { kind ->
                        sql
                            .executeQuery(
                                "SELECT id, policy_json::jsonb->>'preRegistrationCapacity' AS pre, policy_json::jsonb->>'onsiteCapacity' AS onsite, policy_json::jsonb ? 'channels' AS channels FROM tb_${kind}_program ORDER BY id",
                            ).use { rows ->
                                (1..4).forEach { id ->
                                    rows.next() shouldBe true
                                    rows.getInt("id") shouldBe id
                                    rows.getString("pre") shouldBe
                                        when (id) {
                                            3 -> "7"
                                            4 -> null
                                            else -> "10"
                                        }
                                    rows.getString("onsite") shouldBe
                                        when (id) {
                                            3 -> "3"
                                            4 -> null
                                            else -> "0"
                                        }
                                    rows.getBoolean("channels") shouldBe false
                                }
                            }
                    }
                    sql.executeQuery("SELECT count(*) FROM tb_program_policy_v37_backup").use { rows ->
                        rows.next()
                        rows.getInt(1) shouldBe 4
                    }
                    sql
                        .executeQuery(
                            """
                            SELECT bool_and(
                            (p.policy_json::jsonb - 'channels' - 'preRegistrationCapacity' - 'onsiteCapacity')
                            IS NOT DISTINCT FROM
                            (b.policy_json::jsonb - 'channels' - 'preRegistrationCapacity' - 'onsiteCapacity'))
                            FROM policies_before b JOIN (
                                SELECT 'STANDARD' AS kind,id,policy_json FROM tb_standard_program
                                UNION ALL SELECT 'TRAINING',id,policy_json FROM tb_training_program
                            ) p USING(kind,id)
                            """.trimIndent(),
                        ).use { rows ->
                            rows.next()
                            rows.getBoolean(1) shouldBe true
                        }
                    sql
                        .executeQuery(
                            "SELECT bool_and(b.policy_json = p.policy_json) FROM tb_program_policy_v37_backup b JOIN policies_before p ON b.program_type=p.kind AND b.program_id=p.id",
                        ).use { rows ->
                            rows.next()
                            rows.getBoolean(1) shouldBe true
                        }
                    flyway().migrate().migrationsExecuted shouldBe 0
                }
            }
        }
    }
}
