package team.startup.expo.domain.expo.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.expo.entity.ExpoFeatureSetting
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureAppliedResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureGroupResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureReadiness
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureReadinessReason
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureReadinessResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureSettingsResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureStateResponse
import team.startup.expo.domain.expo.presentation.dto.response.ParticipantPhoneVerificationResponse
import team.startup.expo.domain.expo.repository.ExpoFeatureSettingRepository
import team.startup.expo.global.exception.ExpectedException

// 박람회 기능 선택값 읽기·쓰기. 호출자가 트랜잭션과 박람회 행 락을 잡는다.
@Component
class ExpoFeatureSupport(
    private val settings: ExpoFeatureSettingRepository,
    // ponytail: 설정 화면(F09)을 공개하기 전까지 새 박람회도 기존처럼 모두 켠다. 화면 공개와 함께 true로 바꾼다.
    @Value("\${expo.features.new-expo-defaults-enabled:false}") private val newExpoDefaultsEnabled: Boolean,
) {
    // 카탈로그 검증(중복·없는 선행 키·순환)이 실패하면 빈 생성과 함께 앱 시작이 실패한다.
    init {
        ExpoFeatureCatalog.definitions
    }

    fun configured(expoId: String): Map<String, Boolean> =
        ExpoFeatureCatalog.configured(settings.findAllByExpoId(expoId).associate { it.featureKey to it.enabled })

    fun effective(expoId: String): Map<String, Boolean> = ExpoFeatureCatalog.effective(configured(expoId))

    // 내부 조회·이벤트 snapshot용 전체 상태. publicOnly면 참가자용 키만, 선택값 없이 싣는다.
    fun states(
        expoId: String,
        publicOnly: Boolean = false,
    ): List<ExpoFeatureStateResponse> {
        val configured = configured(expoId)
        val effective = ExpoFeatureCatalog.effective(configured)
        return ExpoFeatureCatalog.definitions
            .filter { !publicOnly || it.participantVisible }
            .map { ExpoFeatureStateResponse(it.key, configured.getValue(it.key).takeUnless { publicOnly }, effective.getValue(it.key)) }
    }

    fun enabled(
        expoId: String,
        key: String,
    ): Boolean = effective(expoId).getValue(ExpoFeatureCatalog.definition(key).key)

    // Expo가 직접 받는 신규 쓰기용. 실효 OFF면 409다. 로컬 DB만 읽으므로 박람회 행 락 안에서 불러도 된다.
    fun ensureEnabled(
        expoId: String,
        key: String,
    ) {
        if (!enabled(expoId, key)) {
            throw ExpectedException(HttpStatus.CONFLICT, "${ExpoFeatureCatalog.definition(key).label} 기능이 꺼져 있습니다.", "FEATURE_DISABLED")
        }
    }

    // 새 박람회 기본값. 생성 요청에 프로그램이 있으면 그 종류의 프로그램 기능을 켜서 만든 프로그램이 바로 숨지 않게 한다.
    fun initialize(
        expoId: String,
        hasStandardPrograms: Boolean,
        hasTrainingPrograms: Boolean,
    ) {
        val defaults = ExpoFeatureCatalog.defaults(newExpo = newExpoDefaultsEnabled).toMutableMap()
        if (hasStandardPrograms) defaults["STANDARD_PROGRAM"] = true
        if (hasTrainingPrograms) defaults["TRAINING_PROGRAM"] = true
        save(expoId, defaults)
    }

    fun save(
        expoId: String,
        values: Map<String, Boolean>,
    ) {
        val existing = settings.findAllByExpoId(expoId).associateBy { it.featureKey }
        settings.saveAllAndFlush(
            values.map { (key, enabled) ->
                existing[key]?.also { it.enabled = enabled } ?: ExpoFeatureSetting(expoId, key, enabled)
            },
        )
    }

    // 관리자 응답. 프로그램 준비 상태는 ExpoFeatureReadinessSupport.attach가 락 밖에서 더한다.
    fun view(
        expo: Expo,
        configured: Map<String, Boolean>,
        applied: ExpoFeatureAppliedResponse? = null,
    ): ExpoFeatureSettingsResponse {
        val effective = ExpoFeatureCatalog.effective(configured)
        return ExpoFeatureSettingsResponse(
            expoId = expo.id,
            revision = expo.featureRevision,
            groups = ExpoFeatureGroup.entries.map { ExpoFeatureGroupResponse(it, it.label) },
            features =
                ExpoFeatureCatalog.definitions.mapIndexed { index, definition ->
                    ExpoFeatureResponse(
                        key = definition.key,
                        label = definition.label,
                        description = definition.description,
                        group = definition.group,
                        order = index,
                        requires = definition.requires,
                        configuredEnabled = configured.getValue(definition.key),
                        effectiveEnabled = effective.getValue(definition.key),
                        blockedBy = ExpoFeatureCatalog.blockedBy(definition.key, effective),
                        readiness = readiness(definition.key, effective),
                    )
                },
            authentication =
                ParticipantPhoneVerificationResponse(
                    expo.participantPhoneVerificationRequired,
                    expo.participantPhoneVerificationRevision,
                ),
            applied = applied,
        )
    }

    private fun readiness(
        key: String,
        effective: Map<String, Boolean>,
    ): ExpoFeatureReadinessResponse =
        if (key == "COMPANION_REGISTRATION" && effective["PRE_REGISTRATION"] != true && effective["FIELD_REGISTRATION"] != true) {
            ExpoFeatureReadinessResponse(
                ExpoFeatureReadiness.NEEDS_SETUP,
                listOf(ExpoFeatureReadinessReason("NO_REGISTRATION_PATH", "사전등록이나 현장등록이 켜져 있어야 동행자를 받을 수 있습니다.")),
            )
        } else {
            ExpoFeatureReadinessResponse(ExpoFeatureReadiness.READY)
        }
}
