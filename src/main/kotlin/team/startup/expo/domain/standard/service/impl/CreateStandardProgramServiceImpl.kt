package team.startup.expo.domain.standard.service.impl

import org.springframework.stereotype.Service
import team.startup.expo.domain.standard.presentation.dto.request.AddStandardProgramRequest
import team.startup.expo.domain.standard.service.CreateStandardProgramService
import team.startup.expo.domain.standard.service.StandardProgramCreationSupport

@Service
class CreateStandardProgramServiceImpl(
    private val creation: StandardProgramCreationSupport,
) : CreateStandardProgramService {
    override fun execute(
        expoId: String,
        request: AddStandardProgramRequest,
    ) {
        creation.create(expoId, listOf(request))
    }
}
