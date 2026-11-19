package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoFeatureSettingsRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureAppliedResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureSettingsResponse
import team.startup.expo.domain.expo.presentation.dto.response.ProgramDefinition
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.ExpoEventSupport
import team.startup.expo.domain.expo.service.ExpoFeatureCatalog
import team.startup.expo.domain.expo.service.ExpoFeatureReadinessSupport
import team.startup.expo.domain.expo.service.ExpoFeatureSupport
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.expo.service.UpdateExpoFeatureSettingsService
import team.startup.expo.global.exception.ExpectedException

// 기능 선택값과 전화 인증 값을 박람회 행 락 아래에서 한 번에 바꾼다. revision이 다르면 아무것도 저장하지 않고 409다.
// 락 안에서는 원격 조회를 하지 않는다(#160). 프로그램 준비 상태는 커밋한 뒤 락 밖에서 붙이며, 그 조회가 실패해도 저장은 그대로다.
@Service
class UpdateExpoFeatureSettingsServiceImpl(
    private val expoRepository: ExpoRepository,
    private val policies: ProgramPolicySupport,
    private val features: ExpoFeatureSupport,
    private val events: ExpoEventSupport,
    private val readiness: ExpoFeatureReadinessSupport,
    private val transactions: TransactionTemplate,
) : UpdateExpoFeatureSettingsService {
    override fun execute(
        expoId: String,
        request: UpdateExpoFeatureSettingsRequest,
    ): ExpoFeatureSettingsResponse {
        val expectedRevision = request.expectedRevision ?: throw badRequest("기능 설정 revision이 필요합니다.")
        val changeList = request.changes ?: throw badRequest("바꿀 기능 목록이 필요합니다.")
        val changes = changeList.associate { requireNotNull(it.key) to requireNotNull(it.enabled) }
        if (changes.size != changeList.size) throw badRequest("같은 기능을 두 번 보낼 수 없습니다.")
        val phone = request.participantPhoneVerificationRequired
        if (phone != null && request.expectedPhoneRevision == null) throw badRequest("전화번호 인증 설정 revision이 필요합니다.")

        val (view, programs) =
            requireNotNull(
                transactions.execute {
                    save(expoId, expectedRevision, changes, phone, request.expectedPhoneRevision)
                },
            )
        return readiness.attach(view, programs)
    }

    private fun save(
        expoId: String,
        expectedRevision: Long,
        changes: Map<String, Boolean>,
        phone: Boolean?,
        expectedPhoneRevision: Long?,
    ): Pair<ExpoFeatureSettingsResponse, List<ProgramDefinition>> {
        val expo =
            expoRepository.findLockedById(expoId)
                ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
        policies.ensureAvailable(expoId)
        if (expo.featureRevision != expectedRevision) {
            throw ExpectedException(HttpStatus.CONFLICT, "다른 곳에서 기능 설정이 먼저 바뀌었습니다. 새로 불러온 뒤 다시 저장해 주세요.")
        }
        if (phone != null && expo.participantPhoneVerificationRevision != expectedPhoneRevision) {
            throw ExpectedException(HttpStatus.CONFLICT, "다른 곳에서 전화번호 인증 설정이 먼저 바뀌었습니다. 새로 불러온 뒤 다시 저장해 주세요.")
        }

        val current = features.configured(expoId)
        val plan = ExpoFeatureCatalog.plan(current, changes)
        val featuresChanged = plan.configured != current
        val phoneChanged = phone != null && phone != expo.participantPhoneVerificationRequired
        if (featuresChanged) {
            features.save(expoId, plan.configured)
            expo.increaseFeatureRevision()
        }
        if (phone != null) expo.updateParticipantPhoneVerification(phone)
        if (featuresChanged || phoneChanged) events.changed(expoId)
        return features.view(
            expo,
            plan.configured,
            ExpoFeatureAppliedResponse(plan.autoEnabled, plan.reactivated, plan.deactivated, featuresChanged || phoneChanged),
        ) to readiness.definitions(expoId)
    }

    private fun badRequest(message: String) = ExpectedException(HttpStatus.BAD_REQUEST, message)
}
