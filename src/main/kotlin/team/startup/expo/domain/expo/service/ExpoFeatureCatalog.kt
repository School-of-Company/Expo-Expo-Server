package team.startup.expo.domain.expo.service

import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException

enum class ExpoFeatureGroup(
    val label: String,
) {
    REGISTRATION("참가 등록"),
    ADMISSION("입장"),
    PROGRAM("프로그램"),
    PROGRAM_ATTENDANCE("프로그램 출석"),
    SURVEY("만족도·경품"),
    NOTIFICATION("알림"),
}

// 박람회 기능 한 개. 그룹은 화면 분류일 뿐이며 그룹 ON으로 하위 기능을 켜지 않는다(docs/expo-feature-settings.md).
// legacyDefault: 행이 없는 기존 박람회의 값(종전 동작 보존). newDefault: 새 박람회 기본값.
// participantVisible: 참가자 공개 조회(/expo/{id}/public)에 실효 값을 내보낸다.
data class ExpoFeatureDefinition(
    val key: String,
    val label: String,
    val description: String,
    val group: ExpoFeatureGroup,
    val requires: List<String> = emptyList(),
    val newDefault: Boolean = false,
    val legacyDefault: Boolean = true,
    val participantVisible: Boolean = true,
)

// 기능을 켜며 함께 켜진 선행 기능, 부모가 다시 켜져 살아난 자식, 부모가 꺼져 실효 OFF가 된 자식.
data class ExpoFeaturePlan(
    val configured: Map<String, Boolean>,
    val autoEnabled: List<String>,
    val reactivated: List<String>,
    val deactivated: List<String>,
)

// 기능 정의와 의존 관계 계산. 운영 카탈로그는 ExpoFeatureCatalog 하나이며, 공유 예제(docs/expo-feature-examples.json)의
// 다단계 의존 예제처럼 다른 정의로도 같은 규칙을 검사할 수 있게 클래스로 둔다.
open class ExpoFeatureGraph(
    definitions: List<ExpoFeatureDefinition>,
) {
    val definitions: List<ExpoFeatureDefinition> = validate(definitions)
    val keys: Set<String> = definitions.map { it.key }.toSet()
    private val byKey = definitions.associateBy { it.key }

    fun definition(key: String): ExpoFeatureDefinition = byKey[key] ?: throw ExpectedException(HttpStatus.BAD_REQUEST, "알 수 없는 기능입니다: $key")

    // 저장 행이 없는 키는 legacyDefault로 채운다. 카탈로그에서 빠진 키는 버린다.
    fun configured(stored: Map<String, Boolean>): Map<String, Boolean> =
        definitions.associate {
            it.key to
                (stored[it.key] ?: it.legacyDefault)
        }

    fun defaults(newExpo: Boolean): Map<String, Boolean> =
        definitions.associate {
            it.key to
                if (newExpo) it.newDefault else it.legacyDefault
        }

    // 선택값이 ON이고 모든 선행 기능이 실효 ON일 때만 실효 ON이다.
    fun effective(configured: Map<String, Boolean>): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()

        fun resolve(key: String): Boolean =
            result[key] ?: (configured[key] == true && byKey.getValue(key).requires.all(::resolve)).also { result[key] = it }
        definitions.forEach { resolve(it.key) }
        return definitions.associate { it.key to result.getValue(it.key) }
    }

    // 실효 OFF로 만드는 직접 선행 기능.
    fun blockedBy(
        key: String,
        effective: Map<String, Boolean>,
    ): List<String> = byKey.getValue(key).requires.filter { effective[it] != true }

    // 자식 ON은 선행 기능 선택도 ON으로 바꾼다(여러 단계도 끝까지). 부모 OFF는 자식 선택값을 보존하고 실효 값만 끈다.
    // 같은 요청에서 부모 OFF와 자식 ON을 함께 보내면 뜻이 모순되므로 400이다.
    fun plan(
        current: Map<String, Boolean>,
        changes: Map<String, Boolean>,
    ): ExpoFeaturePlan {
        changes.keys.forEach(::definition)
        val before = configured(current)
        val next = (before + changes).toMutableMap()
        val autoEnabled = mutableSetOf<String>()

        fun enableParents(key: String) {
            byKey.getValue(key).requires.forEach { parent ->
                if (changes[parent] == false) {
                    throw ExpectedException(HttpStatus.BAD_REQUEST, "$parent 을(를) 끄면서 이를 필요로 하는 $key 을(를) 켤 수 없습니다.")
                }
                if (next[parent] != true) {
                    next[parent] = true
                    autoEnabled += parent
                }
                enableParents(parent)
            }
        }
        changes.filterValues { it }.keys.forEach(::enableParents)
        val was = effective(before)
        val now = effective(next)
        val touched = changes.keys + autoEnabled
        return ExpoFeaturePlan(
            configured = definitions.associate { it.key to next.getValue(it.key) },
            autoEnabled = definitions.map { it.key }.filter { it in autoEnabled },
            reactivated = definitions.map { it.key }.filter { it !in touched && was[it] == false && now[it] == true },
            deactivated = definitions.map { it.key }.filter { it !in touched && was[it] == true && now[it] == false },
        )
    }

    private companion object {
        val KEY_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")

        // 중복 키·없는 선행 키·순환을 거부한다. 운영 카탈로그는 ExpoFeatureSupport 빈 생성 때 검사되어 앱 시작이 실패한다.
        fun validate(definitions: List<ExpoFeatureDefinition>): List<ExpoFeatureDefinition> {
            val byKey = definitions.associateBy { it.key }
            require(
                byKey.size == definitions.size,
            ) { "중복된 기능 키가 있습니다: ${definitions.groupBy { it.key }.filterValues { it.size > 1 }.keys}" }
            definitions.forEach { definition ->
                require(definition.key.matches(KEY_PATTERN)) { "기능 키 형식이 올바르지 않습니다: ${definition.key}" }
                definition.requires.forEach { require(it in byKey) { "${definition.key}의 선행 기능 $it 이(가) 없습니다." } }
            }
            val done = mutableSetOf<String>()

            fun visit(
                key: String,
                path: List<String>,
            ) {
                require(key !in path) { "기능 의존 관계에 순환이 있습니다: ${(path + key).joinToString(" -> ")}" }
                if (!done.add(key)) return
                byKey.getValue(key).requires.forEach { visit(it, path + key) }
            }
            definitions.forEach { visit(it.key, emptyList()) }
            return definitions
        }
    }
}

// 코드에 정의한 기능 목록과 의존 관계. 새 기능은 여기와 실제 사용 지점 검사를 함께 추가한다(docs/expo-feature-settings.md 체크리스트).
object ExpoFeatureCatalog : ExpoFeatureGraph(
    listOf(
        ExpoFeatureDefinition(
            "PRE_REGISTRATION",
            "사전등록",
            "행사 전에 참가자가 온라인으로 등록합니다.",
            ExpoFeatureGroup.REGISTRATION,
            newDefault = true,
        ),
        ExpoFeatureDefinition("FIELD_REGISTRATION", "현장등록", "행사 당일 현장에서 참가자를 등록합니다.", ExpoFeatureGroup.REGISTRATION),
        ExpoFeatureDefinition(
            "COMPANION_REGISTRATION",
            "동행자 등록",
            "등록할 때 동행자를 함께 받습니다. 동행자를 받는 등록 경로(사전·현장)도 켜져 있어야 합니다.",
            ExpoFeatureGroup.REGISTRATION,
        ),
        ExpoFeatureDefinition("QR_ADMISSION", "QR 입장", "참가자 QR을 스캔해 입장을 기록합니다.", ExpoFeatureGroup.ADMISSION),
        ExpoFeatureDefinition(
            "PAPER_QR",
            "종이 QR 발급",
            "휴대폰이 없는 참가자에게 인쇄용 QR을 발급합니다.",
            ExpoFeatureGroup.ADMISSION,
            requires = listOf("QR_ADMISSION"),
            participantVisible = false,
        ),
        ExpoFeatureDefinition("STANDARD_PROGRAM", "일반 프로그램", "일반 프로그램 목록을 공개하고 신청을 받습니다.", ExpoFeatureGroup.PROGRAM),
        ExpoFeatureDefinition("TRAINING_PROGRAM", "연수 프로그램", "연수 프로그램 목록을 공개하고 신청을 받습니다.", ExpoFeatureGroup.PROGRAM),
        ExpoFeatureDefinition(
            "STANDARD_ATTENDANCE",
            "일반 프로그램 출석",
            "일반 프로그램 출석을 기록합니다.",
            ExpoFeatureGroup.PROGRAM_ATTENDANCE,
            requires = listOf("STANDARD_PROGRAM"),
            participantVisible = false,
        ),
        ExpoFeatureDefinition(
            "TRAINING_ATTENDANCE",
            "연수 프로그램 출석",
            "연수 프로그램 출석을 기록합니다.",
            ExpoFeatureGroup.PROGRAM_ATTENDANCE,
            requires = listOf("TRAINING_PROGRAM"),
            participantVisible = false,
        ),
        ExpoFeatureDefinition("SURVEY", "만족도 조사", "참가자 만족도 응답을 받습니다.", ExpoFeatureGroup.SURVEY),
        ExpoFeatureDefinition(
            "SURVEY_LOTTERY",
            "설문 경품 추첨",
            "설문 응답자를 대상으로 경품을 추첨합니다. 실제 추첨은 폼의 추첨 운영 설정도 켜져 있어야 합니다.",
            ExpoFeatureGroup.SURVEY,
            requires = listOf("SURVEY"),
        ),
        ExpoFeatureDefinition(
            "AUTO_NOTIFICATION",
            "자동 알림",
            "신청·승급 등 업무 상태가 바뀔 때 자동으로 알림을 보냅니다. 인증번호 발송은 영향을 받지 않습니다.",
            ExpoFeatureGroup.NOTIFICATION,
            participantVisible = false,
        ),
        ExpoFeatureDefinition(
            "BULK_MESSAGE",
            "단체 메시지",
            "관리자가 참가자에게 단체 메시지를 보냅니다.",
            ExpoFeatureGroup.NOTIFICATION,
            participantVisible = false,
        ),
    ),
)
