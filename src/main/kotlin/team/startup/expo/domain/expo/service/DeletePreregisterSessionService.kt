package team.startup.expo.domain.expo.service

interface DeletePreregisterSessionService {
    fun execute(
        expoId: String,
        sessionId: Long,
        revision: Long,
    )
}
