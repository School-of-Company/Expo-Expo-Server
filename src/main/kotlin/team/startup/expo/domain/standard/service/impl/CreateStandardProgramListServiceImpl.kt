package team.startup.expo.domain.standard.service.impl

import org.springframework.stereotype.Service
import team.startup.expo.domain.standard.presentation.dto.request.AddStandardProgramRequest
import team.startup.expo.domain.standard.service.CreateStandardProgramListService
import team.startup.expo.domain.standard.service.StandardProgramCreationSupport

@Service
class CreateStandardProgramListServiceImpl(
    private val creation: StandardProgramCreationSupport,
) : CreateStandardProgramListService {
    override fun execute(
        expoId: String,
        requests: List<AddStandardProgramRequest>,
    ) {
        creation.create(expoId, requests)
    }
}
