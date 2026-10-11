package team.startup.expo.domain.expo.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.expo.domain.expo.presentation.dto.response.ProgramFormUsageResponse
import team.startup.expo.domain.expo.repository.ProgramFormChangeRepository
import team.startup.expo.domain.expo.service.GetProgramFormUsageService
import java.util.UUID

@Service
class GetProgramFormUsageServiceImpl(
    private val forms: ProgramFormChangeRepository,
) : GetProgramFormUsageService {
    @Transactional(readOnly = true)
    override fun execute(formId: UUID): ProgramFormUsageResponse = forms.usage(formId)
}
