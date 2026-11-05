package team.startup.expo.global.client

import feign.Response
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam

@FeignClient(name = "standardUser", url = "\${expo.standard.user-service-url:}", configuration = [UserClientConfiguration::class])
interface StandardUserClient {
    @PostMapping("/internal/standard-participants/resolve", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun resolve(
        @RequestBody body: String,
    ): Response

    @PostMapping("/internal/standard-participants/names", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun names(
        @RequestBody body: String,
    ): Response

    @DeleteMapping("/internal/expos/{expoId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteExpo(
        @PathVariable expoId: String,
    ): Response
}

@FeignClient(name = "trainingUser", url = "\${expo.training.user-service-url:}", configuration = [UserClientConfiguration::class])
interface TrainingUserClient {
    @GetMapping("/internal/expos/{expoId}/trainees/details", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun details(
        @PathVariable expoId: String,
        @RequestParam cursor: Long,
        @RequestParam size: Int,
    ): Response

    @PostMapping("/internal/trainees/resolve", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun resolve(
        @RequestBody body: String,
    ): Response

    @PostMapping("/internal/trainees/resolve-or-create", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun resolveOrCreate(
        @RequestBody body: String,
    ): Response

    @PostMapping("/internal/trainees/names", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun names(
        @RequestBody body: String,
    ): Response
}

@FeignClient(name = "adminUser", url = "\${expo.standard.user-service-url:}", configuration = [PublicClientConfiguration::class])
interface AdminUserClient {
    @GetMapping("/admin/my")
    fun current(
        @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String,
    ): Response
}

@FeignClient(
    name = "application",
    url = "\${expo.standard.application-service-url:}",
    configuration = [ApplicationClientConfiguration::class],
)
interface ApplicationClient {
    @PostMapping("/internal/standard-program-applications", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun applyStandard(
        @RequestBody body: String,
    ): Response

    @GetMapping("/internal/standard-program-applications/program/{programId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun standardApplications(
        @PathVariable programId: Long,
    ): Response

    @DeleteMapping("/internal/standard-program-applications/program/{programId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteStandardApplications(
        @PathVariable programId: Long,
    ): Response

    @PostMapping("/internal/expos/{expoId}/purge", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun purge(
        @PathVariable expoId: String,
        @RequestBody body: String,
    ): Response

    @GetMapping("/internal/expos/{expoId}/program-policy-capabilities", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun capabilities(
        @PathVariable expoId: String,
    ): Response

    @PutMapping("/internal/expos/{expoId}/program-changes/{changeId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun prepareProgram(
        @PathVariable expoId: String,
        @PathVariable changeId: String,
        @RequestBody body: String,
    ): Response

    @PutMapping("/internal/expos/{expoId}/program-changes/{changeId}/guardian-policy", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun prepareGuardian(
        @PathVariable expoId: String,
        @PathVariable changeId: String,
        @RequestBody body: String,
    ): Response

    @PostMapping("/internal/expos/{expoId}/preregister-sessions/{sessionId}/reconciliation")
    fun reconcile(
        @PathVariable expoId: String,
        @PathVariable sessionId: Long,
    ): Response

    @PutMapping(
        "/internal/expos/{expoId}/preregister-sessions/{sessionId}/changes/{revision}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun prepareSession(
        @PathVariable expoId: String,
        @PathVariable sessionId: Long,
        @PathVariable revision: Long,
        @RequestBody body: String,
    ): Response

    @PutMapping("/internal/expos/{expoId}/preregister-sessions/{sessionId}/waiting-capacity", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun adoptWaitingCapacity(
        @PathVariable expoId: String,
        @PathVariable sessionId: Long,
        @RequestBody body: String,
    ): Response
}

@FeignClient(
    name = "trainingApplication",
    url = "\${expo.training.application-service-url:}",
    configuration = [ApplicationClientConfiguration::class],
)
interface TrainingApplicationClient {
    @GetMapping("/internal/training-program-applications/program/{programId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun applications(
        @PathVariable programId: Long,
    ): Response

    @DeleteMapping("/internal/training-program-applications/program/{programId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteApplications(
        @PathVariable programId: Long,
    ): Response

    @GetMapping("/internal/training-program-applications/trainee/{traineeId}/version", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun version(
        @PathVariable traineeId: Long,
    ): Response

    @GetMapping("/internal/training-program-applications/operations/{operationId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun receipt(
        @PathVariable operationId: String,
    ): Response

    @PostMapping("/internal/training-program-applications", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun add(
        @RequestBody body: String,
    ): Response

    @PutMapping("/internal/training-program-applications/trainee/{traineeId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun replace(
        @PathVariable traineeId: Long,
        @RequestBody body: String,
    ): Response
}

@FeignClient(name = "form", url = "\${expo.form-service-url:}", configuration = [FormClientConfiguration::class])
interface FormClient {
    @GetMapping("/internal/forms/by-id/{formId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun byId(
        @PathVariable formId: String,
    ): Response

    @DeleteMapping("/internal/expos/{expoId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteExpo(
        @PathVariable expoId: String,
    ): Response
}

@FeignClient(name = "publicForm", url = "\${expo.form-service-url:}", configuration = [PublicClientConfiguration::class])
interface PublicFormClient {
    @GetMapping("/{resource}/{expoId}")
    fun lookup(
        @PathVariable resource: String,
        @PathVariable expoId: String,
        @RequestParam type: String,
        @RequestParam(required = false) applicationType: String?,
        @RequestHeader(HttpHeaders.CONTENT_TYPE, required = false) contentType: String? = null,
    ): Response
}

@FeignClient(name = "attention", url = "\${expo.attention.service-url:}", configuration = [AttentionClientConfiguration::class])
interface AttentionClient {
    @GetMapping("/internal/program-attendances/standard/{programId}")
    fun standard(
        @PathVariable programId: Long,
    ): Response

    @GetMapping("/internal/program-attendances/training/{programId}")
    fun training(
        @PathVariable programId: Long,
    ): Response

    @DeleteMapping("/internal/program-attendances/standard/{programId}")
    fun deleteStandard(
        @PathVariable programId: Long,
    ): Response

    @DeleteMapping("/internal/program-attendances/training/{programId}")
    fun deleteTraining(
        @PathVariable programId: Long,
    ): Response

    @DeleteMapping("/internal/expos/{expoId}")
    fun deleteExpo(
        @PathVariable expoId: String,
    ): Response
}
