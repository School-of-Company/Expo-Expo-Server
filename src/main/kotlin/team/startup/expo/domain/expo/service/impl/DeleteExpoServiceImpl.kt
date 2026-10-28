package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.repository.ProgramChangeRepository
import team.startup.expo.domain.expo.service.DeleteExpoService
import team.startup.expo.domain.expo.service.ExpoDeletionClient
import team.startup.expo.domain.expo.service.ExpoEventSupport
import team.startup.expo.domain.image.repository.ExpoImageRepository
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.domain.training.repository.TrainingSmsOutboxRepository
import team.startup.expo.global.exception.ExpectedException

@Service
class DeleteExpoServiceImpl(
    private val expos: ExpoRepository,
    private val changes: ProgramChangeRepository,
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
                if (changes.existsById(expoId)) throw ExpectedException(HttpStatus.CONFLICT, "진행 중인 프로그램 변경을 먼저 재시도해 주세요.")
                dependencies.requireConfiguration()
                expo.markDeleting()
                events.changed(expoId)
                ProgramIds(
                    standardPrograms.findByExpoIdOrderByIdAsc(expoId).map { it.id!! },
                    trainingPrograms.findByExpoIdOrderByIdAsc(expoId).map { it.id!! },
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
