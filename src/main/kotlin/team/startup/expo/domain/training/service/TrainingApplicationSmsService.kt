package team.startup.expo.domain.training.service

import team.startup.expo.domain.expo.entity.Expo
import team.startup.expo.domain.training.entity.TrainingProgram

interface TrainingApplicationSmsService {
    fun execute(
        expo: Expo,
        traineeId: Long,
        programs: List<TrainingProgram>,
        mode: String,
        apply: () -> Unit,
    )
}
