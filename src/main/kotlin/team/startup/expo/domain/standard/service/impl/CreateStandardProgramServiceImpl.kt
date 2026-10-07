package team.startup.expo.domain.standard.service.impl

import org.springframework.stereotype.Service
import team.startup.expo.domain.standard.presentation.dto.request.AddStandardProgramRequest
import team.startup.expo.domain.standard.service.CreateStandardProgramListService
import team.startup.expo.domain.standard.service.CreateStandardProgramService

@Service
class CreateStandardProgramServiceImpl(
    private val createStandardProgramListService: CreateStandardProgramListService,
) : CreateStandardProgramService {
    override fun execute(
        expoId: String,
        request: AddStandardProgramRequest,
    ) = createStandardProgramListService.execute(expoId, listOf(request))
}
