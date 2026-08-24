package com.jarvis.server

import com.jarvis.server.config.DeploymentEnvironment
import com.jarvis.server.config.DeploymentSecurityConfig
import com.jarvis.server.config.IpCidr
import com.jarvis.server.config.ServerConfig
import com.jarvis.server.http.ProxyRequestSecurity
import com.jarvis.server.http.RequestOriginResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DeploymentSecurityTest {
    private fun productionEnv() = mutableMapOf(
        "APP_ENV" to "production",
        "BIND_HOST" to "0.0.0.0",
        "PUBLIC_BASE_URL" to "https://api.example.com",
        "PRODUCTION_TLS_TERMINATED" to "true",
        "TRUST_PROXY_HEADERS" to "true",
        "TRUSTED_PROXY_CIDRS" to "172.30.0.2/32",
        "APPLICATION_REPLICA_COUNT" to "1"
    )

    private fun productionDeployment() = DeploymentSecurityConfig(
        environment = DeploymentEnvironment.PRODUCTION,
        bindHost = "0.0.0.0",
        publicBaseUrl = "https://api.example.com",
        tlsTerminatedByProxy = true,
        trustProxyHeaders = true,
        trustedProxyCidrs = listOf(IpCidr.parse("172.30.0.2/32"))
    )

    @Test
    fun `production configuration requires explicit HTTPS proxy contract`() {
        val valid = ServerConfig.fromEnv(productionEnv()::get)
        assertTrue(valid.deployment.isProduction)
        assertEquals("api.example.com", valid.deployment.expectedPublicAuthority)

        for (key in listOf(
            "BIND_HOST", "PUBLIC_BASE_URL", "PRODUCTION_TLS_TERMINATED",
            "TRUST_PROXY_HEADERS", "TRUSTED_PROXY_CIDRS", "APPLICATION_REPLICA_COUNT"
        )) {
            assertFails("missing $key") {
                ServerConfig.fromEnv(productionEnv().apply { remove(key) }::get)
            }
        }
        assertFails("plaintext public URL") {
            ServerConfig.fromEnv(
                productionEnv().apply { this["PUBLIC_BASE_URL"] = "http://api.example.com" }::get
            )
        }
        assertFails("Internet-wide proxy trust") {
            ServerConfig.fromEnv(
                productionEnv().apply { this["TRUSTED_PROXY_CIDRS"] = "0.0.0.0/0" }::get
            )
        }
        assertFails("invalid security boolean") {
            ServerConfig.fromEnv(
                productionEnv().apply { this["TRUST_PROXY_HEADERS"] = "probably" }::get
            )
        }
        assertFails("global production privacy override") {
            ServerConfig.fromEnv(
                productionEnv().apply { this["ALLOW_SENSITIVE_CLOUD"] = "true" }::get
            )
        }
        assertFails("multiple application replicas") {
            ServerConfig.fromEnv(
                productionEnv().apply { this["APPLICATION_REPLICA_COUNT"] = "2" }::get
            )
        }
    }

    @Test
    fun `production provider credentials cannot use plaintext upstream`() {
        val env = productionEnv().apply {
            this["GROQ_API_KEY"] = "configured-secret"
            this["GROQ_BASE_URL"] = "http://provider.internal/v1/chat"
        }
        assertFails("plaintext provider URL") { ServerConfig.fromEnv(env::get) }
    }

    @Test
    fun `development remains loopback HTTP and ignores spoofed proxy identity`() {
        val config = ServerConfig.fromEnv(emptyMap<String, String>()::get)
        assertEquals(DeploymentEnvironment.DEVELOPMENT, config.deployment.environment)
        assertEquals("127.0.0.1", config.deployment.bindHost)

        val result = ProxyRequestSecurity(config.deployment).resolve(
            peerAddress = "203.0.113.10",
            path = "/v1/health",
            headers = mapOf(
                "Host" to "localhost:8080",
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-For" to "198.51.100.7"
            )
        ) as RequestOriginResult.Accepted
        assertEquals("203.0.113.10", result.origin.clientAddress)
        assertEquals("http", result.origin.scheme)
        assertFalse(result.origin.viaTrustedProxy)
    }

    @Test
    fun `trusted proxy supplies HTTPS scheme canonical host and real client IP`() {
        val result = ProxyRequestSecurity(productionDeployment()).resolve(
            peerAddress = "172.30.0.2",
            path = "/v1/license/validate",
            headers = mapOf(
                "Host" to "api.example.com",
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-For" to "198.51.100.44",
                "X-Forwarded-Host" to "api.example.com",
                "X-Real-IP" to "198.51.100.44"
            )
        ) as RequestOriginResult.Accepted

        assertTrue(result.origin.secure)
        assertTrue(result.origin.viaTrustedProxy)
        assertEquals("198.51.100.44", result.origin.clientAddress)
        assertEquals("api.example.com", result.origin.host)
    }

    @Test
    fun `production rejects direct HTTP spoofed scheme and malformed proxy chains`() {
        val security = ProxyRequestSecurity(productionDeployment())
        assertRejected(403, security.resolve(
            "203.0.113.10", "/v1/license/validate",
            mapOf(
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-For" to "198.51.100.44",
                "X-Forwarded-Host" to "api.example.com"
            )
        ))
        assertRejected(426, security.resolve(
            "172.30.0.2", "/v1/license/validate",
            forwarded(proto = "http")
        ))
        assertRejected(400, security.resolve(
            "172.30.0.2", "/v1/license/validate",
            forwarded(forwardedFor = "198.51.100.1, 198.51.100.2")
        ))
        assertRejected(400, security.resolve(
            "172.30.0.2", "/v1/license/validate",
            forwarded(host = "attacker.example")
        ))
    }

    @Test
    fun `production internal health works but loopback authenticated HTTP does not`() {
        val security = ProxyRequestSecurity(productionDeployment())
        val health = security.resolve("127.0.0.1", "/v1/health", mapOf("Host" to "localhost"))
        assertTrue(health is RequestOriginResult.Accepted)
        assertRejected(
            403,
            security.resolve("127.0.0.1", "/v1/admin/metrics", mapOf("Host" to "localhost"))
        )
    }

    @Test
    fun `literal CIDR matching supports IPv4 and IPv6 without hostnames`() {
        assertTrue(IpCidr.parse("172.30.0.0/24").contains("172.30.0.99"))
        assertFalse(IpCidr.parse("172.30.0.0/24").contains("172.31.0.1"))
        assertTrue(IpCidr.parse("2001:db8::/32").contains("2001:db8::10"))
        assertFails("hostname CIDR") { IpCidr.parse("proxy.example/24") }
    }

    @Test
    fun `repository production topology exposes only TLS proxy and sanitizes headers`() {
        val root = repositoryRoot()
        val compose = Files.readString(root.resolve("deploy/docker-compose.production.yml"))
        val caddy = Files.readString(root.resolve("deploy/Caddyfile"))
        val dockerfile = Files.readString(root.resolve("deploy/server.Dockerfile"))

        assertTrue(compose.contains("\"80:80/tcp\""))
        assertTrue(compose.contains("\"443:443/tcp\""))
        assertFalse(compose.contains("8080:8080"))
        assertFalse(compose.contains("5432:5432"))
        assertTrue(compose.contains("internal: true"))
        assertTrue(compose.contains("PRODUCTION_TLS_TERMINATED: \"true\""))
        assertTrue(compose.contains("APPLICATION_REPLICA_COUNT: \"1\""))
        assertTrue(compose.contains("TRUSTED_PROXY_CIDRS: 172.30.0.2/32"))
        assertTrue(compose.contains("ipv4_address: 172.30.0.2"))
        assertTrue(caddy.contains("protocols tls1.2 tls1.3"))
        assertTrue(caddy.contains("http://{\$PUBLIC_DOMAIN}"))
        assertTrue(caddy.contains("redir https://{\$PUBLIC_DOMAIN}{http.request.uri.path} 308"))
        val httpSite = caddy.substringAfter("http://{\$PUBLIC_DOMAIN}")
            .substringBefore("https://{\$PUBLIC_DOMAIN}")
        assertFalse(httpSite.contains("reverse_proxy"))
        assertFalse(caddy.contains("log_credentials"))
        assertTrue(caddy.contains("header_up -Forwarded"))
        for (header in listOf(
            "X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Real-IP"
        )) {
            assertTrue(caddy.contains("header_up $header"))
        }
        assertTrue(dockerfile.contains("USER 10001:10001"))
    }

    private fun forwarded(
        proto: String = "https",
        forwardedFor: String = "198.51.100.44",
        host: String = "api.example.com"
    ) = mapOf(
        "X-Forwarded-Proto" to proto,
        "X-Forwarded-For" to forwardedFor,
        "X-Forwarded-Host" to host
    )

    private fun assertRejected(status: Int, result: RequestOriginResult) {
        assertTrue(result is RequestOriginResult.Rejected)
        assertEquals(status, (result as RequestOriginResult.Rejected).status)
    }

    private fun assertFails(message: String, block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(message, failed)
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(5) {
            if (Files.exists(current.resolve("deploy/Caddyfile"))) return current
            current = current.parent ?: return@repeat
        }
        error("Repository root not found")
    }
}
