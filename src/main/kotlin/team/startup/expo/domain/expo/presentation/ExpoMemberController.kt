package team.startup.expo.domain.expo.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoMemberRequest
import team.startup.expo.domain.expo.presentation.dto.request.CreateExpoStaffInviteRequest
import team.startup.expo.domain.expo.presentation.dto.request.UpdateExpoMemberRequest
import team.startup.expo.domain.expo.presentation.dto.response.ExpoMemberResponse
import team.startup.expo.domain.expo.presentation.dto.response.ExpoStaffInviteResponse
import team.startup.expo.domain.expo.service.CreateExpoMemberService
import team.startup.expo.domain.expo.service.CreateExpoStaffInviteService
import team.startup.expo.domain.expo.service.DeleteExpoMemberService
import team.startup.expo.domain.expo.service.GetExpoMemberListService
import team.startup.expo.domain.expo.service.GetExpoStaffInviteListService
import team.startup.expo.domain.expo.service.RevokeExpoStaffInviteService
import team.startup.expo.domain.expo.service.UpdateExpoMemberService
import java.util.UUID

@RestController
@RequestMapping("/expo/{expo_id}")
class ExpoMemberController(
    private val listMembers: GetExpoMemberListService,
    private val createMember: CreateExpoMemberService,
    private val updateMember: UpdateExpoMemberService,
    private val deleteMember: DeleteExpoMemberService,
    private val listInvites: GetExpoStaffInviteListService,
    private val createInvite: CreateExpoStaffInviteService,
    private val revokeInvite: RevokeExpoStaffInviteService,
) {
    @Operation(summary = "운영진 목록 조회 (OWNER)")
    @GetMapping("/members")
    fun members(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<List<ExpoMemberResponse>> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(listMembers.execute(expoId))

    @Operation(summary = "운영진 추가 (OWNER)")
    @PostMapping("/members")
    fun create(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: CreateExpoMemberRequest,
    ): ResponseEntity<ExpoMemberResponse> = ResponseEntity.status(HttpStatus.CREATED).body(createMember.execute(expoId, request))

    @Operation(summary = "운영진 역할 변경 (OWNER)")
    @PatchMapping("/members/{adminId}")
    fun update(
        @PathVariable("expo_id") expoId: String,
        @Positive @PathVariable adminId: Long,
        @Valid @RequestBody request: UpdateExpoMemberRequest,
    ): ExpoMemberResponse = updateMember.execute(expoId, adminId, request)

    @Operation(summary = "운영진 내보내기 (OWNER)")
    @DeleteMapping("/members/{adminId}")
    fun delete(
        @PathVariable("expo_id") expoId: String,
        @Positive @PathVariable adminId: Long,
    ): ResponseEntity<Void> {
        deleteMember.execute(expoId, adminId)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "스태프 링크 목록 조회 (OWNER, 비밀값 제외)")
    @GetMapping("/staff-invites")
    fun invites(
        @PathVariable("expo_id") expoId: String,
    ): ResponseEntity<List<ExpoStaffInviteResponse>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(listInvites.execute(expoId))

    @Operation(summary = "스태프 링크 생성 (OWNER, 비밀값 최초 한 번 반환)")
    @PostMapping("/staff-invites")
    fun invite(
        @PathVariable("expo_id") expoId: String,
        @Valid @RequestBody request: CreateExpoStaffInviteRequest,
    ): ResponseEntity<ExpoStaffInviteResponse> =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(createInvite.execute(expoId, request))

    @Operation(summary = "스태프 링크 회수 (OWNER)")
    @DeleteMapping("/staff-invites/{inviteId}")
    fun revoke(
        @PathVariable("expo_id") expoId: String,
        @PathVariable inviteId: UUID,
    ): ResponseEntity<Void> {
        revokeInvite.execute(expoId, inviteId)
        return ResponseEntity.noContent().build()
    }
}
