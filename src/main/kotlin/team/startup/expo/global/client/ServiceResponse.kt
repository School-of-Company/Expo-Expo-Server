package team.startup.expo.global.client

import feign.Response

fun Response.bodyText(): String =
    body()
        ?.asInputStream()
        ?.readAllBytes()
        ?.toString(charset())
        .orEmpty()

fun Response.header(name: String): String =
    headers()
        .entries
        .firstOrNull {
            it.key.equals(name, ignoreCase = true)
        }?.value
        ?.firstOrNull()
        .orEmpty()
