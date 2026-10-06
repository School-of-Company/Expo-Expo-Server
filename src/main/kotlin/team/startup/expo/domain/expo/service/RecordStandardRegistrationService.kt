package team.startup.expo.domain.expo.service

interface RecordStandardRegistrationService {
    fun execute(
        expoId: String,
        participantId: Long,
    )
}
