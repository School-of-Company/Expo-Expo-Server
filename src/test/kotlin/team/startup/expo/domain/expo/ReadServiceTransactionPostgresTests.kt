package team.startup.expo.domain.expo

import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.expo.domain.expo.entity.AdminApplicationCancelAccess
import team.startup.expo.domain.expo.entity.AdminApplicationPromoteAccess
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.presentation.dto.request.AdminChangeHistoryRequest
import team.startup.expo.domain.expo.repository.AdminApplicationCancelAccessRepository
import team.startup.expo.domain.expo.repository.AdminApplicationPromoteAccessRepository
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.AdminAuthorityClient
import team.startup.expo.domain.expo.service.GetAdminApplicationCancelAccessService
import team.startup.expo.domain.expo.service.GetAdminApplicationPromoteAccessService
import team.startup.expo.domain.expo.service.GetAdminChangeHistoryService
import team.startup.expo.domain.expo.service.GetAdminExpoAccessService
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.standard.service.GetStandardProgramParticipantsService
import team.startup.expo.domain.standard.service.StandardDependenciesClient
import team.startup.expo.domain.training.entity.Category
import team.startup.expo.domain.training.entity.TrainingProgram
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.GetTrainingProgramTraineesService
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.global.attendance.ProgramAttendanceClient
import team.startup.expo.support.TestJwt

@SpringBootTest(properties = ["eureka.client.enabled=false"])
@EntityScan("team.startup.expo.domain")
@Testcontainers
class ReadServiceTransactionPostgresTests {
    @Autowired private lateinit var dataSource: HikariDataSource

    @Autowired private lateinit var expos: ExpoRepository

    @Autowired private lateinit var cancelGrants: AdminApplicationCancelAccessRepository

    @Autowired private lateinit var promoteGrants: AdminApplicationPromoteAccessRepository

    @Autowired private lateinit var standardPrograms: StandardProgramRepository

    @Autowired private lateinit var trainingPrograms: TrainingProgramRepository

    @Autowired private lateinit var expoAccess: GetAdminExpoAccessService

    @Autowired private lateinit var cancelAccess: GetAdminApplicationCancelAccessService

    @Autowired private lateinit var promoteAccess: GetAdminApplicationPromoteAccessService

    @Autowired private lateinit var history: GetAdminChangeHistoryService

    @Autowired private lateinit var participants: GetStandardProgramParticipantsService

    @Autowired private lateinit var trainees: GetTrainingProgramTraineesService

    @MockitoBean private lateinit var authority: AdminAuthorityClient

    @MockitoBean private lateinit var standard: StandardDependenciesClient

    @MockitoBean private lateinit var training: TrainingDependenciesClient

    @MockitoBean private lateinit var attendance: ProgramAttendanceClient

    @Test
    fun `admin authority calls hold neither a transaction nor a database connection`() {
        val expo = saveExpo("admin-reads")
        cancelGrants.saveAndFlush(AdminApplicationCancelAccess(expo.id, 1))
        promoteGrants.saveAndFlush(AdminApplicationPromoteAccess(expo.id, 1))
        val authorization = "Bearer ${TestJwt.token("ROLE_ADMIN")}"
        var calls = 0
        doAnswer {
            assertNoDatabaseTransaction()
            calls++
            null
        }.`when`(authority).requireCurrentAdmin(authorization)

        expoAccess.execute(expo.id, authorization).adminId shouldBe 1L
        cancelAccess.execute(expo.id, authorization).adminId shouldBe 1L
        promoteAccess.execute(expo.id, authorization).adminId shouldBe 1L
        history.execute(expo.id, authorization, AdminChangeHistoryRequest()).totalElements shouldBe 0L
        calls shouldBe 4
    }

    @Test
    fun `standard application name and attendance calls release the database connection`() {
        val expo = saveExpo("standard-reads")
        val program =
            standardPrograms.saveAndFlush(
                StandardProgram(title = "standard", startedAt = "2030-01-01T09:00", endedAt = "2030-01-01T10:00", expo = expo),
            )
        val id = requireNotNull(program.id)
        val calls = mutableListOf<String>()
        `when`(standard.applications(id)).thenAnswer {
            assertNoDatabaseTransaction()
            calls.add("applications")
            listOf(StandardDependenciesClient.ProgramApplication(9, 42))
        }
        `when`(standard.participantNames(expo.id, listOf(42L))).thenAnswer {
            assertNoDatabaseTransaction()
            calls.add("names")
            listOf(StandardDependenciesClient.ParticipantName(42, "participant"))
        }
        `when`(attendance.standard(id)).thenAnswer {
            assertNoDatabaseTransaction()
            calls.add("attendance")
            emptyMap<Long, ProgramAttendanceClient.Attendance>()
        }

        val response = participants.execute(id).single()
        response.id shouldBe 9L
        response.name shouldBe "participant"
        response.programName shouldBe "standard"
        response.status shouldBe false
        calls shouldBe listOf("applications", "names", "attendance")
    }

    @Test
    fun `training application name and attendance calls release the database connection`() {
        val expo = saveExpo("training-reads")
        val program =
            trainingPrograms.saveAndFlush(
                TrainingProgram(
                    title = "training",
                    startedAt = "2030-01-01T09:00",
                    endedAt = "2030-01-01T10:00",
                    category = Category.CHOICE,
                    expo = expo,
                ),
            )
        val id = requireNotNull(program.id)
        val calls = mutableListOf<String>()
        `when`(training.applications(id)).thenAnswer {
            assertNoDatabaseTransaction()
            calls.add("applications")
            listOf(TrainingDependenciesClient.ProgramApplication(9, 42))
        }
        `when`(training.traineeNames(expo.id, listOf(42L))).thenAnswer {
            assertNoDatabaseTransaction()
            calls.add("names")
            listOf(TrainingDependenciesClient.TraineeName(42, "trainee"))
        }
        `when`(attendance.training(id)).thenAnswer {
            assertNoDatabaseTransaction()
            calls.add("attendance")
            emptyMap<Long, ProgramAttendanceClient.Attendance>()
        }

        val response = trainees.execute(id).single()
        response.id shouldBe 9L
        response.name shouldBe "trainee"
        response.programName shouldBe "training"
        response.status shouldBe false
        calls shouldBe listOf("applications", "names", "attendance")
    }

    private fun saveExpo(id: String): Expo =
        expos.saveAndFlush(Expo(id, "expo", "description", "2030-01-01", "2030-01-02", "place", null, "1", "2", 0, 0))

    private fun assertNoDatabaseTransaction() {
        TransactionSynchronizationManager.isActualTransactionActive() shouldBe false
        TransactionSynchronizationManager.hasResource(dataSource) shouldBe false
        dataSource.hikariPoolMXBean.activeConnections shouldBe 0
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("JWT_PUBLIC_KEY") { TestJwt.publicKeyPem }
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
