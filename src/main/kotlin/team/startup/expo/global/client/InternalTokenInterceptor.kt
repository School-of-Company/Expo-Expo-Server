package team.startup.expo.global.client

import feign.RequestInterceptor
import feign.RequestTemplate

class InternalTokenInterceptor(
    private val token: String,
) : RequestInterceptor {
    override fun apply(template: RequestTemplate) {
        if (token.isNotBlank()) template.header(INTERNAL_TOKEN_HEADER, token)
    }

    private companion object {
        const val INTERNAL_TOKEN_HEADER = "X-Internal-Token"
    }
}
