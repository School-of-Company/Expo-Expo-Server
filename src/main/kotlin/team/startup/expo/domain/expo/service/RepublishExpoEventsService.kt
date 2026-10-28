package team.startup.expo.domain.expo.service

import team.startup.expo.domain.expo.presentation.dto.response.RepublishExpoEventsResponse

interface RepublishExpoEventsService {
    fun execute(): RepublishExpoEventsResponse
}
