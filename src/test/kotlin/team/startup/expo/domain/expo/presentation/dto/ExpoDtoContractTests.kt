package team.startup.expo.domain.expo.presentation.dto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoRequest
import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoRequest
import team.startup.expo.domain.expo.presentation.dto.response.CreateExpoResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoDetailResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoSummaryResponse
import team.startup.expo.domain.training.entity.Category
import team.startup.expo.global.exception.ErrorResponse
import tools.jackson.databind.DatabindException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.readValue
import java.time.LocalDate
import java.time.LocalDateTime

@JsonTest
class ExpoDtoContractTests(
    @Autowired private val objectMapper: ObjectMapper,
) {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `생성 요청은 기존 Web JSON 이름과 날짜 시간 형식을 읽는다`() {
        val request = objectMapper.readValue<CreateExpoRequest>(CREATE_REQUEST_JSON)

        request.title shouldBe "2026 박람회"
        request.startedDay shouldBe LocalDate.of(2026, 9, 24)
        request.finishedDay shouldBe LocalDate.of(2026, 9, 25)
        request.x shouldBe "127.123"
        request.y shouldBe "37.456"
        request.addStandardProRequestDto.single().startedAt shouldBe LocalDateTime.of(2026, 9, 24, 9, 0)
        request.addTrainingProRequestDto.single().category shouldBe Category.ESSENTIAL
        validator.validate(request).isEmpty() shouldBe true
    }

    @Test
    fun `수정 요청은 ID가 없는 프로그램을 신규 항목으로 읽는다`() {
        val request = objectMapper.readValue<UpdateExpoRequest>(UPDATE_REQUEST_JSON)

        request.updateStandardProRequestDto.map { it.id } shouldBe listOf(11L, null)
        request.updateTrainingProRequestDto.map { it.id } shouldBe listOf(21L, null)
        validator.validate(request).isEmpty() shouldBe true
    }

    @Test
    fun `중첩 프로그램 제목은 PostgreSQL 길이 제한 전에 검증한다`() {
        val request =
            objectMapper.readValue<CreateExpoRequest>(
                CREATE_REQUEST_JSON.replace("일반 프로그램", "가".repeat(51)),
            )

        val paths = validator.validate(request).map { it.propertyPath.toString() }

        paths shouldContain "addStandardProRequestDto[0].title"
    }

    @Test
    fun `잘못된 박람회 날짜는 역직렬화에서 거부한다`() {
        shouldThrow<DatabindException> {
            objectMapper.readValue<CreateExpoRequest>(
                CREATE_REQUEST_JSON.replace("2026-09-24\"", "2026/09/24\""),
            )
        }
    }

    @Test
    fun `잘못된 프로그램 시간은 역직렬화에서 거부한다`() {
        shouldThrow<DatabindException> {
            objectMapper.readValue<CreateExpoRequest>(
                CREATE_REQUEST_JSON.replace("2026-09-24 09:00", "2026-09-24T09:00"),
            )
        }
    }

    @Test
    fun `정의되지 않은 연수 Category는 역직렬화에서 거부한다`() {
        shouldThrow<DatabindException> {
            objectMapper.readValue<CreateExpoRequest>(
                CREATE_REQUEST_JSON.replace("ESSENTIAL", "UNKNOWN"),
            )
        }
    }

    @Test
    fun `필수 coverImage가 없으면 역직렬화에서 거부한다`() {
        shouldThrow<DatabindException> {
            objectMapper.readValue<CreateExpoRequest>(
                CREATE_REQUEST_JSON.replace("  \"coverImage\": \"https://example.com/cover.png\",\n", ""),
            )
        }
    }

    @Test
    fun `프로그램 목록의 null 원소는 역직렬화에서 거부한다`() {
        shouldThrow<DatabindException> {
            objectMapper.readValue<CreateExpoRequest>(
                CREATE_REQUEST_JSON.replace(
                    "\"addStandardProRequestDto\": [",
                    "\"addStandardProRequestDto\": [null,",
                ),
            )
        }
    }

    @Test
    fun `필수 프로그램 목록이 null이면 역직렬화에서 거부한다`() {
        val payload = objectMapper.readTree(CREATE_REQUEST_JSON) as ObjectNode
        payload.putNull("addStandardProRequestDto")

        shouldThrow<DatabindException> {
            objectMapper.readValue<CreateExpoRequest>(payload.toString())
        }
    }

    @Test
    fun `생성과 수정 요청은 빈 프로그램 목록을 허용한다`() {
        val createPayload = objectMapper.readTree(CREATE_REQUEST_JSON) as ObjectNode
        createPayload.putArray("addStandardProRequestDto")
        createPayload.putArray("addTrainingProRequestDto")
        val updatePayload = objectMapper.readTree(UPDATE_REQUEST_JSON) as ObjectNode
        updatePayload.putArray("updateStandardProRequestDto")
        updatePayload.putArray("updateTrainingProRequestDto")

        val createRequest = objectMapper.readValue<CreateExpoRequest>(createPayload.toString())
        val updateRequest = objectMapper.readValue<UpdateExpoRequest>(updatePayload.toString())

        createRequest.addStandardProRequestDto shouldBe emptyList()
        createRequest.addTrainingProRequestDto shouldBe emptyList()
        updateRequest.updateStandardProRequestDto shouldBe emptyList()
        updateRequest.updateTrainingProRequestDto shouldBe emptyList()
    }

    @Test
    fun `수정 요청의 잘못된 날짜 시간과 Category는 역직렬화에서 거부한다`() {
        val invalidPayloads =
            listOf(
                UPDATE_REQUEST_JSON.replace("2026-09-24\"", "2026/09/24\""),
                UPDATE_REQUEST_JSON.replace("2026-09-24 09:00", "2026-09-24T09:00"),
                UPDATE_REQUEST_JSON.replace("ESSENTIAL", "UNKNOWN"),
            )

        invalidPayloads.forEach { payload ->
            shouldThrow<DatabindException> {
                objectMapper.readValue<UpdateExpoRequest>(payload)
            }
        }
    }

    @Test
    fun `수정 요청의 중첩 제목과 좌표 길이를 검증한다`() {
        val request =
            objectMapper.readValue<UpdateExpoRequest>(
                UPDATE_REQUEST_JSON
                    .replace("기존 일반", "가".repeat(51))
                    .replace("127.123", "1234567890123456"),
            )

        val paths = validator.validate(request).map { it.propertyPath.toString() }

        paths shouldContain "x"
        paths shouldContain "updateStandardProRequestDto[0].title"
    }

    @Test
    fun `수정 요청의 필수 목록 누락과 null 목록 원소를 거부한다`() {
        val missingListPayload = objectMapper.readTree(UPDATE_REQUEST_JSON) as ObjectNode
        missingListPayload.remove("updateTrainingProRequestDto")
        val nullListPayload = objectMapper.readTree(UPDATE_REQUEST_JSON) as ObjectNode
        nullListPayload.putNull("updateStandardProRequestDto")
        val nullElementPayload =
            UPDATE_REQUEST_JSON.replace(
                "\"updateTrainingProRequestDto\": [",
                "\"updateTrainingProRequestDto\": [null,",
            )

        listOf(missingListPayload.toString(), nullListPayload.toString(), nullElementPayload).forEach { payload ->
            shouldThrow<DatabindException> {
                objectMapper.readValue<UpdateExpoRequest>(payload)
            }
        }
    }

    @Test
    fun `좌표 길이와 수정 프로그램 ID 양수 조건을 검증한다`() {
        val createRequest =
            objectMapper.readValue<CreateExpoRequest>(
                CREATE_REQUEST_JSON.replace("127.123", "1234567890123456"),
            )
        val updateRequest = objectMapper.readValue<UpdateExpoRequest>(UPDATE_REQUEST_JSON.replace("\"id\": 11", "\"id\": 0"))

        validator.validate(createRequest).map { it.propertyPath.toString() } shouldContain "x"
        validator.validate(updateRequest).map { it.propertyPath.toString() } shouldContain
            "updateStandardProRequestDto[0].id"
    }

    @Test
    fun `생성 목록 상세 응답은 기존 JSON 필드만 직렬화한다`() {
        objectMapper.writeValueAsString(CreateExpoResponse(expoId = "123456789012-1234-1234-1234-12345678")) shouldBe
            "{\"expoId\":\"123456789012-1234-1234-1234-12345678\"}"

        objectMapper.writeValueAsString(
            ExpoSummaryResponse(
                id = "123456789012-1234-1234-1234-12345678",
                title = "2026 박람회",
                description = "설명",
                startedDay = "2026-09-24",
                finishedDay = "2026-09-25",
                coverImage = null,
            ),
        ) shouldBe
            "{\"id\":\"123456789012-1234-1234-1234-12345678\",\"title\":\"2026 박람회\",\"description\":\"설명\",\"startedDay\":\"2026-09-24\",\"finishedDay\":\"2026-09-25\",\"coverImage\":null}"

        objectMapper.writeValueAsString(
            ExpoDetailResponse(
                title = "2026 박람회",
                description = "설명",
                startedDay = "2026-09-24",
                finishedDay = "2026-09-25",
                location = "서울",
                coverImage = null,
                x = "127.123",
                y = "37.456",
            ),
        ) shouldBe
            "{\"title\":\"2026 박람회\",\"description\":\"설명\",\"startedDay\":\"2026-09-24\",\"finishedDay\":\"2026-09-25\",\"location\":\"서울\",\"coverImage\":null,\"x\":\"127.123\",\"y\":\"37.456\"}"
    }

    @Test
    fun `오류 응답은 status와 message만 직렬화한다`() {
        objectMapper.writeValueAsString(ErrorResponse(status = 409, message = "프로그램 정책 충돌")) shouldBe
            "{\"status\":409,\"message\":\"프로그램 정책 충돌\"}"
    }

    companion object {
        private val CREATE_REQUEST_JSON =
            """
            {
              "title": "2026 박람회",
              "description": "설명",
              "startedDay": "2026-09-24",
              "finishedDay": "2026-09-25",
              "location": "서울",
              "coverImage": "https://example.com/cover.png",
              "x": 127.123,
              "y": 37.456,
              "addStandardProRequestDto": [
                {
                  "title": "일반 프로그램",
                  "startedAt": "2026-09-24 09:00",
                  "endedAt": "2026-09-24 10:00"
                }
              ],
              "addTrainingProRequestDto": [
                {
                  "title": "연수 프로그램",
                  "startedAt": "2026-09-24 10:00",
                  "endedAt": "2026-09-24 11:00",
                  "category": "ESSENTIAL"
                }
              ]
            }
            """.trimIndent()

        private val UPDATE_REQUEST_JSON =
            """
            {
              "title": "수정 박람회",
              "description": "수정 설명",
              "startedDay": "2026-09-24",
              "finishedDay": "2026-09-25",
              "location": "서울",
              "coverImage": "https://example.com/cover.png",
              "x": 127.123,
              "y": 37.456,
              "updateStandardProRequestDto": [
                {
                  "id": 11,
                  "title": "기존 일반",
                  "startedAt": "2026-09-24 09:00",
                  "endedAt": "2026-09-24 10:00"
                },
                {
                  "title": "신규 일반",
                  "startedAt": "2026-09-24 11:00",
                  "endedAt": "2026-09-24 12:00"
                }
              ],
              "updateTrainingProRequestDto": [
                {
                  "id": 21,
                  "title": "기존 연수",
                  "startedAt": "2026-09-24 13:00",
                  "endedAt": "2026-09-24 14:00",
                  "category": "ESSENTIAL"
                },
                {
                  "title": "신규 연수",
                  "startedAt": "2026-09-24 15:00",
                  "endedAt": "2026-09-24 16:00",
                  "category": "CHOICE"
                }
              ]
            }
            """.trimIndent()
    }
}
