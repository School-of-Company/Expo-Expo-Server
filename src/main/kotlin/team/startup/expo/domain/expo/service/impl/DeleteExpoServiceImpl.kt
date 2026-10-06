package team.startup.expo.domain.expo.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.startup.expo.domain.expo.repository.ExpoRepository
import team.startup.expo.domain.expo.service.DeleteExpoService
import team.startup.expo.domain.expo.service.ExpoDeletionClient
import team.startup.expo.domain.standard.repository.StandardProgramRepository
import team.startup.expo.domain.training.repository.TrainingProgramRepository
import team.startup.expo.global.exception.ExpectedException

@Service
class DeleteExpoServiceImpl(
    private val expos: ExpoRepository,
    private val standardPrograms: StandardProgramRepository,
    private val trainingPrograms: TrainingProgramRepository,
    private val dependencies: ExpoDeletionClient,
    private val transactions: TransactionTemplate,
) : DeleteExpoService {
    override fun execute(expoId: String) {
        val programs =
            transactions.execute {
                val expo =
                    expos.findLockedById(expoId)
                        ?: throw ExpectedException(HttpStatus.NOT_FOUND, "박람회를 찾지 못 했습니다.")
                dependencies.requireConfiguration()
                expo.markDeleting()
                ProgramIds(
                    standardPrograms.findByExpoIdOrderByIdAsc(expoId).map { it.id!! },
                    trainingPrograms.findByExpoIdOrderByIdAsc(expoId).map { it.id!! },
                )
            }

        dependencies.deleteApplications(expoId, programs.standard, programs.training)
        dependencies.deleteForms(expoId)
        dependencies.deleteUsers(expoId)

        transactions.executeWithoutResult {
            expos.findLockedById(expoId)?.let(expos::delete)
        }
    }

    private data class ProgramIds(
        val standard: List<Long>,
        val training: List<Long>,
    )
}
