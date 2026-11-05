package team.startup.expo.global.swagger

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ApiError(status = 400, description = "입력 값·요청 형식·기간·식별자가 올바르지 않습니다.")
annotation class ApiInputError

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ApiError(status = 404, description = "요청한 박람회·프로그램·회차·이미지 또는 연동 대상이 없습니다.")
annotation class ApiNotFoundError

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ApiError(status = 409, description = "삭제·변경 진행 상태, 버전, 신청 이력, 정원 또는 이미지 연결이 충돌합니다.")
annotation class ApiConflictError

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ApiError(status = 502, description = "연동 서비스 응답 상태 또는 본문이 올바르지 않습니다.")
annotation class ApiDownstreamError

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ApiError(status = 503, description = "연동 설정이 없거나 서비스·이미지 저장소에 연결할 수 없습니다.")
annotation class ApiUnavailableError

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ApiDownstreamError
@ApiUnavailableError
annotation class ApiIntegrationErrors

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiInputError
@ApiNotFoundError
@ApiConflictError
@ApiIntegrationErrors
annotation class ApiMutationErrors
