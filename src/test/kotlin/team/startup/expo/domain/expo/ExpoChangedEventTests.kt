package team.startup.expo.domain.expo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.entity.PreregisterSession
import team.startup.expo.domain.expo.messaging.ExpoChangedEvent
import team.startup.expo.domain.expo.messaging.ExpoEventSnapshot
import team.startup.expo.domain.expo.presentation.dto.response.ProgramChangePayload
import team.startup.expo.domain.expo.presentation.dto.response.ProgramDefinition
import team.startup.expo.domain.expo.presentation.dto.response.ProgramMutation
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.standard.entity.StandardProgram
import team.startup.expo.domain.training.entity.Category
import team.startup.expo.domain.training.entity.TrainingProgram
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

class ExpoChangedEventTests {
    private val mapper = JsonMapper.builder().build()
    private val expo = Expo("expo-1", "제목", "설명", "2030-01-01", "2030-01-02", "광주", null, "127", "37", 3, 1, 10, 20)
    private val group = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `스냅샷은 박람회와 프로그램·회차의 현재 상태를 id 순서로 담는다`() {
        val snapshot =
            ExpoEventSnapshot.of(
                expo,
                listOf(
                    StandardProgram(2, "일반 B", "2030-01-01T11:00", "2030-01-01T12:00", expo, group, null, "1관", 3),
                    StandardProgram(1, "일반 A", "2030-01-01 09:00", "2030-01-01 10:00", expo, group, """{"formId":"f-1"}"""),
                ),
                listOf(TrainingProgram(5, "연수", "2030-01-02T09:00", "2030-01-02T10:00", Category.CHOICE, expo, group)),
                listOf(
                    PreregisterSession(
                        7,
                        expo,
                        "1회차",
                        Instant.parse("2030-01-01T01:00:00Z"),
                        Instant.parse("2030-01-01T02:00:00Z"),
                        "강당",
                        30,
                        5,
                        false,
                        2,
                        teacherCapacity = 10,
                        nonTeacherCapacity = 20,
                    ),
                ),
                mapper,
            )

        val json = mapper.readTree(mapper.writeValueAsString(snapshot))
        json.path("expo").path("title").asString() shouldBe "제목"
        json.path("expo").path("preregisterTeacherCapacity").asInt() shouldBe 10
        json.path("expo").path("participantPhoneVerificationRequired").asBoolean() shouldBe true
        json.path("expo").path("deleting").asBoolean() shouldBe false
        json.path("expo").path("programChangePending").asBoolean() shouldBe false
        json.path("standardPrograms")[0].has("pendingRevision") shouldBe true
        json.path("standardPrograms")[0].path("pendingRevision").isNull shouldBe true
        json.path("expo").has("applicationPerson") shouldBe false
        json.path("standardPrograms").size() shouldBe 2
        json.path("standardPrograms")[0].path("id").asLong() shouldBe 1
        json.path("standardPrograms")[1].path("id").asLong() shouldBe 2
        // 예전 공백 형식 저장값도 ISO 지역 일시로 맞춘다
        json.path("standardPrograms")[0].path("startedAt").asString() shouldBe "2030-01-01T09:00"
        json
            .path("standardPrograms")[0]
            .path("policy")
            .path("formId")
            .asString() shouldBe "f-1"
        json.path("standardPrograms")[1].path("policy").isNull shouldBe true
        json.path("standardPrograms")[1].path("revision").asLong() shouldBe 3
        json.path("standardPrograms")[1].path("groupId").asString() shouldBe group.toString()
        json.path("trainingPrograms")[0].path("category").asString() shouldBe "CHOICE"
        json.path("preregisterSessions")[0].path("startedAt").asString() shouldBe "2030-01-01T01:00:00Z"
        json.path("preregisterSessions")[0].path("capacity").asInt() shouldBe 30
        json.path("preregisterSessions")[0].path("revision").asLong() shouldBe 2
    }

    @Test
    fun `진행 중인 프로그램 변경이 있으면 programChangePending과 바뀌는 프로그램의 pendingRevision을 싣는다`() {
        val standard = StandardProgram(1, "일반", "2030-01-01T09:00", "2030-01-01T10:00", expo, group, null, null, 3)
        val training = TrainingProgram(5, "연수", "2030-01-02T09:00", "2030-01-02T10:00", Category.CHOICE, expo, group)
        val previous =
            ProgramDefinition("expo-1", ProgramType.STANDARD, 1, "일반", "2030-01-01T09:00", "2030-01-01T10:00", null, group, null, 3)
        val change = ProgramChangePayload(listOf(ProgramMutation(previous, previous.copy(title = "수정", revision = 4))))

        val snapshot = ExpoEventSnapshot.of(expo, listOf(standard), listOf(training), emptyList(), mapper, change)

        snapshot.expo.programChangePending shouldBe true
        snapshot.standardPrograms[0].pendingRevision shouldBe 4
        snapshot.trainingPrograms[0].pendingRevision shouldBe null
    }

    @Test
    fun `프로그램을 바꾸지 않는 진행 중 변경도 programChangePending은 true다`() {
        val snapshot = ExpoEventSnapshot.of(expo, emptyList(), emptyList(), emptyList(), mapper, ProgramChangePayload(emptyList()))

        snapshot.expo.programChangePending shouldBe true
    }

    @Test
    fun `삭제 중인 박람회는 deleting이 true다`() {
        expo.markDeleting()

        val snapshot = ExpoEventSnapshot.of(expo, emptyList(), emptyList(), emptyList(), mapper)

        snapshot.expo.deleting shouldBe true
    }

    @Test
    fun `메시지는 봉투 필드와 스냅샷을 한 객체로 합친다`() {
        val snapshot = mapper.writeValueAsString(ExpoEventSnapshot.of(expo, emptyList(), emptyList(), emptyList(), mapper))

        val message =
            mapper.readTree(
                ExpoChangedEvent.message(mapper, "event-1", 42, Instant.parse("2030-01-01T00:00:00Z"), "expo-1", snapshot),
            )

        message.path("schemaVersion").asInt() shouldBe 1
        message.path("eventId").asString() shouldBe "event-1"
        message.path("version").asLong() shouldBe 42
        message.path("occurredAt").asString() shouldBe "2030-01-01T00:00:00Z"
        message.path("expoId").asString() shouldBe "expo-1"
        message.path("deleted").asBoolean() shouldBe false
        message.path("expo").path("title").asString() shouldBe "제목"
        message.path("standardPrograms").isArray shouldBe true
    }

    @Test
    fun `삭제 메시지는 상태 없이 deleted만 true다`() {
        val message =
            mapper.readTree(ExpoChangedEvent.message(mapper, "event-2", 43, Instant.parse("2030-01-01T00:00:00Z"), "expo-1", null))

        message.path("deleted").asBoolean() shouldBe true
        message.path("expoId").asString() shouldBe "expo-1"
        message.has("expo") shouldBe false
        message.has("standardPrograms") shouldBe false
    }
}
