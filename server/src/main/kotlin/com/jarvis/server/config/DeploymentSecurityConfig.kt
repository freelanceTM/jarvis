package com.jarvis.server.config

import java.net.InetAddress
import java.net.URI

enum class DeploymentEnvironment {
    DEVELOPMENT,
    TEST,
    PRODUCTION;

    companion object {
        fun parse(value: String): DeploymentEnvironment = when (value.trim().lowercase()) {
            "development", "dev" -> DEVELOPMENT
            "test" -> TEST
            "production", "prod" -> PRODUCTION
            else -> throw IllegalArgumentException(
                "APP_ENV must be development, test, or production"
            )
        }
    }
}

/** A validated literal IPv4/IPv6 CIDR; hostnames and wildcard trust are rejected. */
class IpCidr private constructor(
    private val network: ByteArray,
    val prefixLength: Int,
    private val rendered: String
) {
    fun contains(address: String): Boolean {
        val candidate = parseLiteralAddress(address) ?: return false
        if (candidate.size != network.size) return false
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        for (index in 0 until fullBytes) {
            if (candidate[index] != network[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (candidate[fullBytes].toInt() and mask) ==
            (network[fullBytes].toInt() and mask)
    }

    val isUnrestricted: Boolean get() = prefixLength == 0

    override fun toString(): String = rendered

    companion object {
        fun parse(value: String): IpCidr {
            val parts = value.trim().split('/', limit = 2)
            require(parts.size == 2) { "Trusted proxy entries must use CIDR notation" }
            val address = parseLiteralAddress(parts[0])
                ?: throw IllegalArgumentException("Trusted proxy CIDR must contain a literal IP address")
            val maxBits = address.size * 8
            val prefix = parts[1].toIntOrNull()
                ?: throw IllegalArgumentException("Trusted proxy CIDR prefix is invalid")
            require(prefix in 0..maxBits) { "Trusted proxy CIDR prefix is out of range" }

            val normalized = address.copyOf()
            val fullBytes = prefix / 8
            val remainingBits = prefix % 8
            if (remainingBits != 0) {
                val mask = (0xff shl (8 - remainingBits)) and 0xff
                normalized[fullBytes] = (normalized[fullBytes].toInt() and mask).toByte()
            }
            for (index in (fullBytes + if (remainingBits == 0) 0 else 1) until normalized.size) {
                normalized[index] = 0
            }
            return IpCidr(normalized, prefix, value.trim())
        }

        internal fun canonicalAddress(value: String): String? =
            parseLiteralAddress(value)?.let { InetAddress.getByAddress(it).hostAddress }

        private fun parseLiteralAddress(raw: String): ByteArray? {
            val value = raw.trim().substringBefore('%')
            if (value.isEmpty()) return null
            val isIpv4 = value.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) &&
                value.split('.').all { part ->
                    part.toIntOrNull()?.let { number -> number in 0..255 } == true
                }
            val isIpv6 = ':' in value && value.matches(Regex("[0-9A-Fa-f:.]+"))
            if (!isIpv4 && !isIpv6) return null
            return runCatching { InetAddress.getByName(value).address }.getOrNull()
        }
    }
}

data class DeploymentSecurityConfig(
    val environment: DeploymentEnvironment = DeploymentEnvironment.DEVELOPMENT,
    val bindHost: String = "127.0.0.1",
    val publicBaseUrl: String? = null,
    val tlsTerminatedByProxy: Boolean = false,
    val trustProxyHeaders: Boolean = false,
    val trustedProxyCidrs: List<IpCidr> = emptyList(),
    /** Declared orchestrator replica count; ADR-0001 currently permits exactly one. */
    val applicationReplicaCount: Int = 1
) {
    val isProduction: Boolean get() = environment == DeploymentEnvironment.PRODUCTION
    val expectedPublicAuthority: String? = publicBaseUrl?.let(::normalizedAuthority)

    init {
        require(bindHost.isNotBlank() && bindHost.length <= 253 &&
            bindHost.none { it.isWhitespace() || it == '/' }) {
            "BIND_HOST must be a host or literal address"
        }
        publicBaseUrl?.let { url ->
            val parsed = parsePublicUrl(url)
            require(parsed.scheme == "https" || !isProduction) {
                "PUBLIC_BASE_URL must use https in production"
            }
        }
        require(applicationReplicaCount >= 1) {
            "APPLICATION_REPLICA_COUNT must be at least 1"
        }
        require(trustedProxyCidrs.none(IpCidr::isUnrestricted)) {
            "TRUSTED_PROXY_CIDRS must not trust all Internet addresses"
        }
        if (isProduction) {
            require(applicationReplicaCount == 1) {
                "ADR-0001 permits exactly one production application instance"
            }
            require(tlsTerminatedByProxy) {
                "Production requires PRODUCTION_TLS_TERMINATED=true"
            }
            require(trustProxyHeaders) {
                "Production requires TRUST_PROXY_HEADERS=true"
            }
            require(trustedProxyCidrs.isNotEmpty()) {
                "Production requires at least one TRUSTED_PROXY_CIDRS entry"
            }
            require(!publicBaseUrl.isNullOrBlank()) {
                "Production requires PUBLIC_BASE_URL=https://..."
            }
        }
    }

    private fun normalizedAuthority(url: String): String {
        val parsed = parsePublicUrl(url)
        val host = parsed.host.lowercase()
        val port = parsed.port
        return if (port == -1 || (parsed.scheme == "https" && port == 443)) host else "$host:$port"
    }

    private fun parsePublicUrl(url: String): URI {
        val parsed = runCatching { URI(url) }.getOrElse {
            throw IllegalArgumentException("PUBLIC_BASE_URL is invalid", it)
        }
        require(parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank()) {
            "PUBLIC_BASE_URL must be an absolute HTTP(S) URL"
        }
        require(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null) {
            "PUBLIC_BASE_URL must not contain credentials, query, or fragment"
        }
        require(parsed.path.isNullOrEmpty() || parsed.path == "/") {
            "PUBLIC_BASE_URL must not contain a path"
        }
        return parsed
    }
}
