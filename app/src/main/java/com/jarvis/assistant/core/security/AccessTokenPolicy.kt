package com.jarvis.assistant.core.security

/** Contract shared with server static-client-token validation. */
object AccessTokenPolicy {
    const val MIN_LENGTH = 32
    const val MAX_LENGTH = 256

    fun isValid(token: String): Boolean =
        token.length in MIN_LENGTH..MAX_LENGTH && token.none { it.isWhitespace() || it.isISOControl() }
}
