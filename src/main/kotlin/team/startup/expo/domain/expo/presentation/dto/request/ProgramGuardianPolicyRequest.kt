package team.startup.expo.domain.expo.presentation.dto.request

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import team.startup.expo.global.exception.ExpectedException

@Schema(description = "해당 회차의 초등 1~3학년 보호자 동반 필수 정책. 문항 제목과 선택지 라벨은 판정에 사용하지 않음")
data class ProgramGuardianPolicyRequest(
    @field:Positive
    @field:Schema(description = "참조하는 프로그램 폼 버전", example = "1")
    val formVersion: Long,
    @field:Positive
    @field:Schema(description = "학교급 선택 문항 ID", example = "101")
    val schoolTypeQuestionId: Long,
    @field:NotBlank
    @field:Schema(description = "초등학생 선택지의 jsonData 키", example = "ELEMENTARY_STUDENT")
    val elementarySchoolValue: String,
    @field:Positive
    @field:Schema(description = "초등학생 선택 시 표시하는 필수 학년 문항 ID", example = "102")
    val gradeQuestionId: Long,
    @field:Schema(description = "학년 선택지 jsonData 키와 학년(1~6)의 일대일 대응", example = "{\"g1\":1,\"g2\":2,\"g3\":3,\"g4\":4,\"g5\":5,\"g6\":6}")
    val gradeValues: Map<String, Int>,
    // 필드가 없는 기존 정책은 null로 읽고 다시 저장할 때도 생략한다.
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:Schema(description = "보호자 선택지의 jsonData 키. 보호자는 학교급 문항에서 이 키를 골라야 함", example = "GUARDIAN")
    val guardianSchoolValue: String? = null,
) {
    fun validate() {
        if (formVersion <= 0 || schoolTypeQuestionId <= 0 || gradeQuestionId <= 0 ||
            schoolTypeQuestionId == gradeQuestionId || elementarySchoolValue.isBlank() ||
            gradeValues.size != 6 || gradeValues.keys.any { it.isBlank() } || gradeValues.values.toSet() != (1..6).toSet() ||
            (guardianSchoolValue != null && (guardianSchoolValue.isBlank() || guardianSchoolValue == elementarySchoolValue))
        ) {
            throw ExpectedException(HttpStatus.BAD_REQUEST, "보호자 정책의 학교급·학년 참조가 올바르지 않습니다.")
        }
    }
}
