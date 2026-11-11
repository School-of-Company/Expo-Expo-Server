package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.startup.expo.domain.expo.presentation.dto.response.ExpoValidResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoValidationResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.ExpoRoleSupport
import team.startup.expo.domain.expo.service.FormLookupClient
import team.startup.expo.domain.expo.service.GetExpoValidationService
import team.startup.expo.global.exception.ExpectedException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

@Service
class GetExpoValidationServiceImpl(
    private val expoRepository: ExpoRepository,
    private val forms: FormLookupClient,
    private val roles: ExpoRoleSupport,
) : GetExpoValidationService {
    override fun execute(): ExpoValidationResponse {
        val visible = roles.visibleRoles()
        val myRoles = if (roles.enabled) visible ?: roles.currentRoles() else emptyMap()
        val expoIds = if (visible == null) expoRepository.findAllIds() else visible.keys.toList()
        if (expoIds.isNotEmpty()) forms.requireConfiguration()

        val results =
            expoIds.map { expoId ->
                val preStandard = forms.lookup("forms", expoId, "STANDARD", "PRE")
                val siteStandard = forms.lookup("forms", expoId, "STANDARD", "FIELD")
                val preTrainee = forms.lookup("forms", expoId, "TRAINEE", "PRE")
                val fieldTrainee = forms.lookup("forms", expoId, "TRAINEE", "FIELD")
                val standardSurvey = forms.lookup("surveys", expoId, "STANDARD")
                val traineeSurvey = forms.lookup("surveys", expoId, "TRAINEE")
                CompletableFuture
                    .allOf(preStandard, siteStandard, preTrainee, fieldTrainee, standardSurvey, traineeSurvey)
                    .thenApply {
                        ExpoValidResponse(
                            expoId = expoId,
                            myRole = myRoles[expoId],
                            preStandardFormCreatedStatus = preStandard.join(),
                            siteStandardFormCreatedStatus = siteStandard.join(),
                            traineeFormCreatedStatus = preTrainee.join() || fieldTrainee.join(),
                            standardSurveyCreatedStatus = standardSurvey.join(),
                            traineeSurveyCreatedStatus = traineeSurvey.join(),
                        )
                    }
            }
        try {
            return ExpoValidationResponse(results.map { it.join() })
        } catch (exception: CompletionException) {
            throw exception.cause as? ExpectedException
                ?: ExpectedException(HttpStatus.BAD_GATEWAY, "Form 서비스에 연결할 수 없습니다.")
        }
    }
}
