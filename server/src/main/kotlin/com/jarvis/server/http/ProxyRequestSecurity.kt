package com.jarvis.server.http

import com.jarvis.server.config.DeploymentSecurityConfig
import com.jarvis.server.config.IpCidr
import java.net.InetAddress
import java.net.URI

data class RequestOrigin(
    val clientAddress: String,
    val scheme: String,
    val host: String?,
    val viaTrustedProxy: Boolean
) {
    val secure: Boolean get() = scheme == "https"
}

sealed interface RequestOriginResult {
    data class Accepted(val origin: RequestOrigin) : RequestOriginResult
    data class Rejected(val status: Int, val code: String) : RequestOriginResult
}

/**
 * Resolves original request metadata only from explicitly trusted proxy peers.
 * Production direct traffic is rejected before authentication or request-body parsing.
 */
class ProxyRequestSecurity(private val config: DeploymentSecurityConfig) {
    private val proxyHeaders = setOf(
        "x-forwarded-proto", "x-forwarded-for", "x-forwarded-host", "x-real-ip", "forwarded"
    )

    fun resolve(
        peerAddress: String,
        path: String,
        headers: Map<String, String>
    ): RequestOriginResult {
        val canonicalPeer = IpCidr.canonicalAddress(peerAddress)
            ?: return RequestOriginResult.Rejected(400, "INVALID_PEER_ADDRESS")
        val peer = runCatching { InetAddress.getByName(canonicalPeer) }.getOrNull()
            ?: return RequestOriginResult.Rejected(400, "INVALID_PEER_ADDRESS")
        val loopback = peer.isLoopbackAddress
        val trustedProxy = config.trustedProxyCidrs.any { it.contains(canonicalPeer) }
        val hasProxyHeaders = headers.keys.any { it.lowercase() in proxyHeaders }

        if (loopback && !hasProxyHeaders) {
            if (config.isProduction && path != "/v1/health") {
                return RequestOriginResult.Rejected(403, "DIRECT_HTTP_FORBIDDEN")
            }
            return RequestOriginResult.Accepted(
                RequestOrigin(canonicalPeer, "http", header(headers, "Host"), false)
            )
        }

        if (!trustedProxy || !config.trustProxyHeaders) {
            if (config.isProduction) {
                return RequestOriginResult.Rejected(403, "UNTRUSTED_PROXY")
            }
            // Development HTTP remains usable, but attacker-supplied forwarded headers are ignored.
            return RequestOriginResult.Accepted(
                RequestOrigin(canonicalPeer, "http", header(headers, "Host"), false)
            )
        }

        // Active reverse-proxy health checks are private and intentionally have no forwarded headers.
        if (!hasProxyHeaders && path == "/v1/health") {
            return RequestOriginResult.Accepted(
                RequestOrigin(canonicalPeer, "http", header(headers, "Host"), true)
            )
        }

        val proto = singleHeaderToken(headers, "X-Forwarded-Proto")?.lowercase()
            ?: return RequestOriginResult.Rejected(400, "INVALID_FORWARDED_PROTO")
        if (proto !in setOf("http", "https")) {
            return RequestOriginResult.Rejected(400, "INVALID_FORWARDED_PROTO")
        }
        if (config.isProduction && proto != "https") {
            return RequestOriginResult.Rejected(426, "HTTPS_REQUIRED")
        }

        val forwardedFor = singleHeaderToken(headers, "X-Forwarded-For")
            ?: return RequestOriginResult.Rejected(400, "INVALID_FORWARDED_FOR")
        val clientAddress = IpCidr.canonicalAddress(forwardedFor)
            ?: return RequestOriginResult.Rejected(400, "INVALID_FORWARDED_FOR")

        val forwardedHost = singleHeaderToken(headers, "X-Forwarded-Host")
            ?: return RequestOriginResult.Rejected(400, "INVALID_FORWARDED_HOST")
        val authority = normalizeAuthority(proto, forwardedHost)
            ?: return RequestOriginResult.Rejected(400, "INVALID_FORWARDED_HOST")
        if (config.isProduction && authority != config.expectedPublicAuthority) {
            return RequestOriginResult.Rejected(400, "UNEXPECTED_FORWARDED_HOST")
        }

        return RequestOriginResult.Accepted(
            RequestOrigin(clientAddress, proto, authority, true)
        )
    }

    private fun singleHeaderToken(headers: Map<String, String>, name: String): String? {
        val value = header(headers, name)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        // The supported topology has one Internet-facing Caddy proxy which replaces these headers.
        return value.takeIf { ',' !in it && '\r' !in it && '\n' !in it }
    }

    private fun normalizeAuthority(scheme: String, value: String): String? = runCatching {
        if ('/' in value || '@' in value || '?' in value || '#' in value) return null
        val parsed = URI("$scheme://$value")
        val host = parsed.host?.lowercase() ?: return null
        val port = parsed.port
        if (port == -1 || (scheme == "https" && port == 443) || (scheme == "http" && port == 80)) {
            host
        } else {
            "$host:$port"
        }
    }.getOrNull()

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
