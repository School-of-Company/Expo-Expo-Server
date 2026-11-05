package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.DeleteExpoService
import team.startup.expo.domain.expo.service.ExpoDeletionClient
import team.startup.expo.domain.expo.service.ExpoEventSupport
import team.startup.expo.domain.expo.service.ProgramPolicySupport
import team.startup.expo.domain.image.repository.ExpoImageRepository
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository
import team.startup.expo.global.exception.ExpectedException

@Service
class DeleteExpoServiceImpl(
    private val expos: ExpoRepository,
    private val policies: ProgramPolicySupport,
    private val standardPrograms: StandardProgramRepository,
    private val trainingPrograms: TrainingProgramRepository,
    private val images: ExpoImageRepository,
    private val dependencies: ExpoDeletionClient,
    private val transactions: TransactionTemplate,
    private val operations: TrainingSmsOutboxRepository,
    private val events: ExpoEventSupport,
) : DeleteExpoService {
    override fun execute(expoId: String) {
        val programs =
            transactions.execute {
                val expo =
                    expos.findLockedById(expoId)
                        ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
                if (operations.hasUnresolvedApplications(expoId)) {
                    throw ExpectedException(HttpStatus.CONFLICT, "확인 중인 연수 신청이 있어 박람회를 삭제할 수 없습니다.")
                }
                // 삭제 중 표시가 남은 원격 삭제 실패도 같은 명령으로 재시도한다.
                policies.ensureAvailable(expoId, allowDeleting = true)
                dependencies.requireConfiguration()
                expo.markDeleting()
                events.changed(expoId)
                ProgramIds(
                    standardPrograms.findByExpoIdOrderByIdAsc(expoId).map { requireNotNull(it.id) },
                    trainingPrograms.findByExpoIdOrderByIdAsc(expoId).map { requireNotNull(it.id) },
                )
            }

        dependencies.deleteApplications(expoId, programs.standard, programs.training)
        dependencies.deleteForms(expoId)
        dependencies.deleteUsers(expoId)
        dependencies.deleteAttendances(expoId, programs.standard, programs.training)

        transactions.executeWithoutResult {
            expos.findLockedById(expoId)?.let { expo ->
                images.findAllByExpoId(expoId).forEach { it.detach() }
                expos.delete(expo)
                events.changed(expoId)
            }
        }
    }

    private data class ProgramIds(
        val standard: List<Long>,
        val training: List<Long>,
    )
}
