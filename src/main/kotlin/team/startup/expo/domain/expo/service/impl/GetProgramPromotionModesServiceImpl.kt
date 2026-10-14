package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.request.ProgramPromotionMode
import team.startup.expo.domain.expo.presentation.dto.response.ProgramPromotionModeItem
import team.startup.expo.domain.expo.presentation.dto.response.ProgramPromotionModesResponse
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.GetProgramPromotionModesService
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.global.exception.ExpectedException

@Service
class GetProgramPromotionModesServiceImpl(
    private val expos: ExpoRepository,
    private val standards: StandardProgramRepository,
    private val trainings: TrainingProgramRepository,
    private val policies: ProgramPolicySupport,
) : GetProgramPromotionModesService {
    @Transactional(readOnly = true)
    override fun execute(expoId: String): ProgramPromotionModesResponse {
        if (!expos.existsById(expoId)) throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
        val programs =
            (
                standards.findByExpoIdOrderByIdAsc(expoId).map(policies::definition) +
                    trainings.findByExpoIdOrderByIdAsc(expoId).map(policies::definition)
            ).mapNotNull { definition ->
                definition.policy?.let {
                    ProgramPromotionModeItem(
                        definition.programType,
                        requireNotNull(definition.id),
                        definition.title,
                        it.promotionMode,
                        it.promotionEndedAt,
                    )
                }
            }
        val manual = programs.count { it.promotionMode == ProgramPromotionMode.MANUAL }
        return ProgramPromotionModesResponse(programs.size, programs.size - manual, manual, programs)
    }
}
