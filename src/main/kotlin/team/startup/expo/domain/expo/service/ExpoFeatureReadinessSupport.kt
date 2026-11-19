package team.startup.expo.domain.expo.service

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureReadiness
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureReadinessReason
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureReadinessResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureSettingsResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoProgramReadinessResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoProgramSessionReadinessResponse
import team.startup.expo.domain.expo.presentation.dto.response.ProgramDefinition
import team.startup.expo.domain.expo.presentation.dto.response.ProgramRecruitmentPhase
import team.startup.expo.domain.expo.presentation.dto.response.ProgramType
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant

// 관리자 기능 설정 응답의 외부 준비 상태. 원격 조회는 트랜잭션과 박람회 행 락 밖에서만 한다(#160).
// - 프로그램(STANDARD_PROGRAM·TRAINING_PROGRAM, #267): 회차별. 기존 신청 검증(ProgramPolicySupport.checkForms: 정책 값·폼 연결·
//   대기/승급 조건·Application 지원 여부)을 그대로 쓴다.
// - 설문(SURVEY·SURVEY_LOTTERY, #302): Form POST /internal/surveys/summaries의 설문 존재·lotteryEnabled·lotteryNumbersConfigured.
@Component
class ExpoFeatureReadinessSupport(
    private val standards: StandardProgramRepository,
    private val trainings: TrainingProgramRepository,
    private val policies: ProgramPolicySupport,
    private val client: ProgramPolicyClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    // 트랜잭션 안에서 부른다. 로컬 DB만 읽는다.
    fun definitions(expoId: String): List<ProgramDefinition> =
        standards.findByExpoIdOrderByIdAsc(expoId).map(policies::definition) +
            trainings.findByExpoIdOrderByIdAsc(expoId).map(policies::definition)

    // 트랜잭션 밖에서 부른다. 조회가 실패해도 예외를 던지지 않고 UNKNOWN으로 답한다.
    fun attach(
        view: ExpoFeatureSettingsResponse,
        definitions: List<ProgramDefinition>,
    ): ExpoFeatureSettingsResponse {
        val checks =
            try {
                policies.checkForms(definitions)
            } catch (exception: RuntimeException) {
                logger.warn("Program readiness lookup failed: expoId={}", view.expoId, exception)
                null
            }
        val surveys =
            try {
                client.surveySummaries(view.expoId)
            } catch (exception: RuntimeException) {
                logger.warn("Survey readiness lookup failed: expoId={}", view.expoId, exception)
                null
            }
        val byType = definitions.groupBy { it.programType }
        return view.copy(
            features =
                view.features.map { feature ->
                    when (feature.key) {
                        "SURVEY" -> {
                            feature.copy(readiness = survey(surveys))
                        }

                        "SURVEY_LOTTERY" -> {
                            feature.copy(readiness = lottery(surveys))
                        }

                        else -> {
                            val type = PROGRAM_FEATURES[feature.key] ?: return@map feature
                            val program = program(byType[type].orEmpty(), checks)
                            feature.copy(readiness = summary(program), programReadiness = program)
                        }
                    }
                },
        )
    }

    private fun survey(surveys: JsonNode?): ExpoFeatureReadinessResponse =
        when {
            surveys == null -> ExpoFeatureReadinessResponse(ExpoFeatureReadiness.UNKNOWN, listOf(SURVEY_LOOKUP_FAILED))
            surveys.isEmpty -> ExpoFeatureReadinessResponse(ExpoFeatureReadiness.NEEDS_SETUP, listOf(NO_SURVEY))
            else -> ExpoFeatureReadinessResponse(ExpoFeatureReadiness.READY)
        }

    // 실제 추첨은 기능 ON과 Form의 추첨 운영(lotteryEnabled)·당첨 번호가 모두 필요하다. 두 조건을 모두 갖춘 설문이 하나라도 있으면 READY.
    // 추첨 필드가 없는 구형 Form 응답은 판단하지 못한 것이므로 UNKNOWN이다.
    private fun lottery(surveys: JsonNode?): ExpoFeatureReadinessResponse {
        if (surveys == null) return ExpoFeatureReadinessResponse(ExpoFeatureReadiness.UNKNOWN, listOf(SURVEY_LOOKUP_FAILED))
        if (surveys.isEmpty) return ExpoFeatureReadinessResponse(ExpoFeatureReadiness.NEEDS_SETUP, listOf(NO_SURVEY))
        val items = (0 until surveys.size()).map(surveys::get)
        if (items.any { !it.path("lotteryEnabled").isBoolean || !it.path("lotteryNumbersConfigured").isBoolean }) {
            return ExpoFeatureReadinessResponse(ExpoFeatureReadiness.UNKNOWN, listOf(SURVEY_LOOKUP_FAILED))
        }
        if (items.any { it.get("lotteryEnabled").asBoolean() && it.get("lotteryNumbersConfigured").asBoolean() }) {
            return ExpoFeatureReadinessResponse(ExpoFeatureReadiness.READY)
        }
        // 추첨을 시작한 설문이 있으면 그 설문에 빠진 것은 당첨 번호다.
        val reasons =
            if (items.any { it.get("lotteryEnabled").asBoolean() }) {
                listOf(LOTTERY_NUMBERS_MISSING)
            } else {
                listOfNotNull(
                    LOTTERY_NOT_STARTED,
                    LOTTERY_NUMBERS_MISSING.takeIf { items.none { it.get("lotteryNumbersConfigured").asBoolean() } },
                )
            }
        return ExpoFeatureReadinessResponse(ExpoFeatureReadiness.NEEDS_SETUP, reasons)
    }

    private fun program(
        definitions: List<ProgramDefinition>,
        checks: Map<Any, ProgramPolicySupport.FormCheck>?,
    ): ExpoProgramReadinessResponse {
        val sessions = definitions.map { session(it, checks) }
        return ExpoProgramReadinessResponse(sessions.size, sessions.count { it.status == ExpoFeatureReadiness.READY }, sessions)
    }

    private fun session(
        definition: ProgramDefinition,
        checks: Map<Any, ProgramPolicySupport.FormCheck>?,
    ): ExpoProgramSessionReadinessResponse {
        val policy = definition.policy
        val (status, reasons) =
            when {
                // 정책 없는 기존 회차는 폼 없이 기존 신청 API로 받는다.
                policy == null -> {
                    ExpoFeatureReadiness.READY to emptyList()
                }

                checks == null -> {
                    ExpoFeatureReadiness.UNKNOWN to listOf(LOOKUP_FAILED)
                }

                else -> {
                    val check = checks[policies.formKey(definition)]
                    val failure = check?.failure
                    when {
                        check == null -> {
                            ExpoFeatureReadiness.UNKNOWN to listOf(LOOKUP_FAILED)
                        }

                        check.version == null -> {
                            ExpoFeatureReadiness.UNAVAILABLE to listOf(reason("FORM_CHANGE_PENDING", "프로그램 폼 변경이 진행 중이거나 복구가 필요합니다."))
                        }

                        failure == null -> {
                            ExpoFeatureReadiness.READY to emptyList()
                        }

                        failure.status.is5xxServerError -> {
                            ExpoFeatureReadiness.UNKNOWN to listOf(reason("LOOKUP_FAILED", failure.message))
                        }

                        failure.status == HttpStatus.NOT_FOUND -> {
                            ExpoFeatureReadiness.NEEDS_SETUP to
                                listOf(reason("FORM_NOT_FOUND", failure.message))
                        }

                        else -> {
                            ExpoFeatureReadiness.NEEDS_SETUP to listOf(reason("POLICY_INVALID", failure.message))
                        }
                    }
                }
            }
        return ExpoProgramSessionReadinessResponse(requireNotNull(definition.id), definition.title, status, reasons, phase(definition))
    }

    private fun phase(definition: ProgramDefinition): ProgramRecruitmentPhase {
        val policy = definition.policy ?: return ProgramRecruitmentPhase.NO_POLICY
        if (policy.closed) return ProgramRecruitmentPhase.CLOSED
        val now = Instant.now(clock)
        val windows =
            listOfNotNull(
                policy.applicationStartedAt to policy.applicationEndedAt,
                policy.onsiteStartedAt?.let { start -> policy.onsiteEndedAt?.let { start to it } },
            )
        return when {
            windows.any { (start, end) -> !now.isBefore(start) && now.isBefore(end) } -> ProgramRecruitmentPhase.OPEN
            windows.any { (start, _) -> now.isBefore(start) } -> ProgramRecruitmentPhase.UPCOMING
            else -> ProgramRecruitmentPhase.ENDED
        }
    }

    // 준비된 회차가 하나라도 있으면 READY다. 모든 회차가 동시에 준비될 필요는 없다.
    private fun summary(program: ExpoProgramReadinessResponse): ExpoFeatureReadinessResponse {
        val statuses = program.sessions.map { it.status }
        return when {
            program.totalSessions == 0 -> {
                ExpoFeatureReadinessResponse(ExpoFeatureReadiness.NEEDS_SETUP, listOf(reason("NO_SESSIONS", "회차가 없습니다.")))
            }

            program.readySessions > 0 -> {
                ExpoFeatureReadinessResponse(ExpoFeatureReadiness.READY)
            }

            ExpoFeatureReadiness.UNKNOWN in statuses -> {
                ExpoFeatureReadinessResponse(ExpoFeatureReadiness.UNKNOWN, listOf(LOOKUP_FAILED))
            }

            ExpoFeatureReadiness.UNAVAILABLE in statuses -> {
                ExpoFeatureReadinessResponse(
                    ExpoFeatureReadiness.UNAVAILABLE,
                    listOf(reason("NO_READY_SESSIONS", "지금 신청을 받을 수 있는 회차가 없습니다.")),
                )
            }

            else -> {
                ExpoFeatureReadinessResponse(ExpoFeatureReadiness.NEEDS_SETUP, listOf(reason("NO_READY_SESSIONS", "준비가 끝난 회차가 없습니다.")))
            }
        }
    }

    private fun reason(
        code: String,
        message: String,
    ) = ExpoFeatureReadinessReason(code, message)

    private companion object {
        val logger = LoggerFactory.getLogger(ExpoFeatureReadinessSupport::class.java)
        val PROGRAM_FEATURES = mapOf("STANDARD_PROGRAM" to ProgramType.STANDARD, "TRAINING_PROGRAM" to ProgramType.TRAINING)
        val SURVEY_LOOKUP_FAILED = ExpoFeatureReadinessReason("LOOKUP_FAILED", "Form에서 설문·추첨 준비 정보를 확인하지 못했습니다.")
        val NO_SURVEY = ExpoFeatureReadinessReason("NO_SURVEY", "만족도 조사 설문이 없습니다.")
        val LOTTERY_NOT_STARTED = ExpoFeatureReadinessReason("LOTTERY_NOT_STARTED", "설문의 추첨 운영이 시작되지 않았습니다.")
        val LOTTERY_NUMBERS_MISSING = ExpoFeatureReadinessReason("LOTTERY_NUMBERS_MISSING", "당첨 번호가 정해지지 않았습니다.")
        val LOOKUP_FAILED = ExpoFeatureReadinessReason("LOOKUP_FAILED", "폼·신청 서비스에서 준비 정보를 확인하지 못했습니다.")
    }
}
