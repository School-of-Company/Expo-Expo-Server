package team.startup.expo.domain.training.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.training.presentation.dto.request.ApplyTrainingProgramsRequest
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.service.ApplyTrainingProgramListService
import team.startup.expo.domain.training.service.TrainingApplicationSmsService
import team.startup.expo.domain.training.service.TrainingDependenciesClient
import team.startup.expo.domain.training.service.TrainingOperationType
import team.startup.expo.global.exception.ExpectedException

@Service
class ApplyTrainingProgramListServiceImpl(
    private val expos: ExpoRepository,
    private val programs: TrainingProgramRepository,
    private val dependencies: TrainingDependenciesClient,
    private val sms: TrainingApplicationSmsService,
    private val transactions: TransactionTemplate,
) : ApplyTrainingProgramListService {
    override fun execute(request: ApplyTrainingProgramsRequest) {
        val ids = request.trainingProIds
        if (ids.isEmpty() || ids.any { it <= 0 } || ids.distinct().size != ids.size) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "연수 프로그램 ID가 올바르지 않습니다.")
        }
        val (expo, found) =
            requireNotNull(
                transactions.execute {
                    val found = programs.findAllByIdIn(ids)
                    if (found.size != ids.size) throw ExpectedException(HttpStatus.NOT_FOUND, "연수 프로그램을 찾지 못했습니다.")
                    if (found.any { it.expo == null }) throw ExpectedException(HttpStatus.BAD_GATEWAY, "프로그램의 박람회 정보가 없습니다.")
                    val expoId = requireNotNull(found.first().expo).id
                    if (found.any { it.expo?.id != expoId }) throw ExpectedException(HttpStatus.NOT_FOUND, "연수 프로그램을 찾지 못했습니다.")
                    val expo = expos.findLockedById(expoId) ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
                    if (expo.deletingAt != null) throw ExpectedException(HttpStatus.CONFLICT, "삭제 중인 박람회입니다.")
                    expo to found
                },
            )
        val expoId = expo.id
        dependencies.requireApplicationConfiguration()
        val traineeId = dependencies.resolveTrainee(expoId, request.trainingId)
        sms.execute(expo, traineeId, found, TrainingOperationType.ADD)
    }
}
