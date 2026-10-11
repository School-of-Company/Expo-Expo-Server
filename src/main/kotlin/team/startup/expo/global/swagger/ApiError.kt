package team.startup.expo.global.swagger

@Repeatable
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ApiError(
    val status: Int,
    val description: String,
)
