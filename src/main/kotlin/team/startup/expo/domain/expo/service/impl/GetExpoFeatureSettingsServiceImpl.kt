package team.startup.expo.domain.expo.service.impl

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.presentation.dto.response.ExpoFeatureSettingsResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.ExpoFeatureReadinessSupport
import team.startup.expo.domain.expo.service.ExpoFeatureSupport
import team.startup.expo.domain.expo.service.GetExpoFeatureSettingsService
import team.startup.expo.global.exception.ExpectedException

// 로컬 값은 읽기 전용 트랜잭션에서 읽고, 프로그램 준비 상태의 원격 조회는 트랜잭션이 끝난 뒤에 한다.
@Service
class GetExpoFeatureSettingsServiceImpl(
    private val expoRepository: ExpoRepository,
    private val features: ExpoFeatureSupport,
    private val readiness: ExpoFeatureReadinessSupport,
    transactionManager: PlatformTransactionManager,
) : GetExpoFeatureSettingsService {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    override fun execute(expoId: String): ExpoFeatureSettingsResponse {
        val (view, programs) =
            requireNotNull(
                reads.execute {
                    val expo =
                        expoRepository.findByIdOrNull(expoId)
                            ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾을 수 없습니다.")
                    features.view(expo, features.configured(expoId)) to readiness.definitions(expoId)
                },
            )
        return readiness.attach(view, programs)
    }
}
