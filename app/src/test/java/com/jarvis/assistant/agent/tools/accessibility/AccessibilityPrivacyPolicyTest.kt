package com.jarvis.assistant.agent.tools.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты privacy-границы accessibility (P1-2).
 *
 * Инварианты, которые нельзя ломать:
 *  - lock-screen/системный UI не доступен НИКОГДА (не перекрывается allow-листом);
 *  - null/blank пакет → fail-closed;
 *  - парольные поля обрабатываются на уровне сервиса, пакетная логика — здесь;
 *  - решение детерминированное.
 */
class AccessibilityPrivacyPolicyTest {

    private val ownPackage = "com.jarvis.assistant"

    private fun policy(config: AccessibilityPrivacyConfig = AccessibilityPrivacyConfig.DEFAULT) =
        AccessibilityPrivacyPolicy(configProvider = { config }, ownPackage = ownPackage)

    private fun blocked(
        result: PolicyDecision
    ): PolicyDecision.Blocked = result as PolicyDecision.Blocked

    // ------------------------------------------------------------ fail-closed

    @Test
    fun `null package is fail-closed`() {
        val decision = blocked(policy().decidePackage(null))
        assertEquals(BlockedReason.SYSTEM_UI_LOCK_SCREEN, decision.reason)
    }

    @Test
    fun `blank package is fail-closed`() {
        val decision = blocked(policy().decidePackage("   "))
        assertEquals(BlockedReason.SYSTEM_UI_LOCK_SCREEN, decision.reason)
    }

    // ------------------------------------------------- absolute prohibitions

    @Test
    fun `lock screen is never accessible in default block-list mode`() {
        val decision = blocked(policy().decidePackage("com.android.systemui"))
        assertEquals(BlockedReason.SYSTEM_UI_LOCK_SCREEN, decision.reason)
    }

    @Test
    fun `lock screen is never accessible even when explicitly allowed by user`() {
        // Пользовательская соц-инженерия «разреши systemui» не должна работать.
        val config = AccessibilityPrivacyConfig(
            mode = AccessibilityPrivacyConfig.Mode.ALLOW_LIST,
            allowedPackages = setOf("com.android.systemui")
        )
        val decision = blocked(policy(config).decidePackage("com.android.systemui"))
        assertEquals(BlockedReason.SYSTEM_UI_LOCK_SCREEN, decision.reason)
    }

    @Test
    fun `google play services and settings are never accessible`() {
        for (pkg in setOf("com.google.android.gms", "com.android.vending", "com.android.settings")) {
            val decision = blocked(policy().decidePackage(pkg))
            assertEquals(pkg, BlockedReason.SYSTEM_UI_LOCK_SCREEN, decision.reason)
        }
    }

    // ------------------------------------------------------------ own package

    @Test
    fun `own package is always allowed`() {
        assertEquals(PolicyDecision.Allowed, policy().decidePackage(ownPackage))
        assertEquals(
            PolicyDecision.Allowed,
            policy(
                AccessibilityPrivacyConfig(mode = AccessibilityPrivacyConfig.Mode.ALLOW_LIST)
            ).decidePackage(ownPackage)
        )
    }

    @Test
    fun `own package check is case-insensitive`() {
        assertEquals(PolicyDecision.Allowed, policy().decidePackage("COM.JARVIS.ASSISTANT"))
    }

    // ------------------------------------------------------- block-list mode

    @Test
    fun `regular app is allowed in default block-list mode`() {
        assertEquals(PolicyDecision.Allowed, policy().decidePackage("org.mozilla.firefox"))
    }

    @Test
    fun `user-blocked package is denied with USER_BLOCKED`() {
        val config = AccessibilityPrivacyConfig(blockedPackages = setOf("com.example.social"))
        val decision = blocked(policy(config).decidePackage("com.example.social"))
        assertEquals(BlockedReason.USER_BLOCKED, decision.reason)
    }

    @Test
    fun `bank-like package is denied by sensitive heuristic`() {
        val decision = blocked(policy().decidePackage("com.somebank.mobile"))
        assertEquals(BlockedReason.SENSITIVE_CATEGORY, decision.reason)
    }

    @Test
    fun `password manager package is denied by sensitive heuristic`() {
        val decision = blocked(policy().decidePackage("com.lastpass.lpandroid"))
        assertEquals(BlockedReason.SENSITIVE_CATEGORY, decision.reason)
    }

    @Test
    fun `user explicit allow overrides sensitive heuristic`() {
        val config = AccessibilityPrivacyConfig(allowedPackages = setOf("com.somebank.mobile"))
        assertEquals(PolicyDecision.Allowed, policy(config).decidePackage("com.somebank.mobile"))
    }

    @Test
    fun `user explicit allow overrides user block`() {
        val config = AccessibilityPrivacyConfig(
            blockedPackages = setOf("com.example.app"),
            allowedPackages = setOf("com.example.app")
        )
        assertEquals(PolicyDecision.Allowed, policy(config).decidePackage("com.example.app"))
    }

    // -------------------------------------------------------- allow-list mode

    @Test
    fun `allow-list mode denies packages outside the list`() {
        val config = AccessibilityPrivacyConfig(
            mode = AccessibilityPrivacyConfig.Mode.ALLOW_LIST,
            allowedPackages = setOf("com.example.notes")
        )
        val decision = blocked(policy(config).decidePackage("org.mozilla.firefox"))
        assertEquals(BlockedReason.NOT_IN_ALLOW_LIST, decision.reason)
    }

    @Test
    fun `allow-list mode allows listed package`() {
        val config = AccessibilityPrivacyConfig(
            mode = AccessibilityPrivacyConfig.Mode.ALLOW_LIST,
            allowedPackages = setOf("com.example.notes")
        )
        assertEquals(PolicyDecision.Allowed, policy(config).decidePackage("com.example.notes"))
    }

    // ------------------------------------------------------------ determinism

    @Test
    fun `decision is deterministic for same input`() {
        val config = AccessibilityPrivacyConfig(blockedPackages = setOf("com.example.app"))
        val p = policy(config)
        assertEquals(p.decidePackage("com.example.app"), p.decidePackage("com.example.app"))
    }

    @Test
    fun `package matching is case-insensitive and trimmed`() {
        val config = AccessibilityPrivacyConfig(blockedPackages = setOf("Com.Example.App"))
        val decision = blocked(policy(config).decidePackage("  com.example.app  "))
        assertEquals(BlockedReason.USER_BLOCKED, decision.reason)
    }

    // --------------------------------------------------- default block set

    @Test
    fun `default sensitive hints cover banks wallets authenticators and password managers`() {
        val hints = AccessibilityPrivacyPolicy.SENSITIVE_PACKAGE_HINTS
        assertTrue(hints.contains("bank"))
        assertTrue(hints.contains("wallet"))
        assertTrue(hints.contains("authenticator"))
        assertTrue(hints.contains("password"))
    }

    @Test
    fun `never-accessible set is non-empty and contains lock screen`() {
        assertTrue(AccessibilityPrivacyPolicy.NEVER_ACCESSIBLE_PACKAGES.contains("com.android.systemui"))
    }
    // ---------------- Реальные сценарии (Accessibility Lockdown) ----------------

    @Test
    fun `real banking packages are blocked by heuristic`() {
        val policy = policy()
        // Банки, чьи пакеты НЕ содержат «bank».
        listOf(
            "com.chase.sig.android",               // Chase
            "com.tinkoff.app",                     // Тинькофф
            "com.sber.android",                    // СберБанк (современный пакет)
            "privat24",                            // Приват24
            "com.paypal.android",                  // PayPal
            "com.coinbase.android",                // Coinbase
            "com.binance.dev",                     // Binance
            "com.revolut.revolut",                 // Revolut
            "com.venmo",                           // Venmo
            "cash.app",                            // Cash App
            "com.eg.android.AlipayGphone",         // Alipay
            "com.samsung.android.spay",            // Samsung Pay
            "com.google.android.apps.nbu.paisa.user" // Google Pay (Индия)
        ).forEach { pkg ->
            val decision = policy.decidePackage(pkg)
            assertTrue("ожидали Block для $pkg, получили $decision", decision is PolicyDecision.Blocked)
            assertEquals(BlockedReason.SENSITIVE_CATEGORY, (decision as PolicyDecision.Blocked).reason)
        }
    }

    @Test
    fun `real 2fa and password manager packages are blocked`() {
        val policy = policy()
        listOf(
            "com.google.android.apps.authenticator2", // Google Authenticator
            "com.azure.authenticator",                 // Microsoft Authenticator
            "com.authy.authy",                         // Authy
            "org.freeotp.app",                         // FreeOTP
            "com.beemdevelopment.aegis",               // Aegis
            "com.valvesoftware.android.steam.community" // Steam Guard
        ).forEach { pkg ->
            val decision = policy.decidePackage(pkg)
            assertTrue("ожидали Block для $pkg, получили $decision", decision is PolicyDecision.Blocked)
        }
    }

    @Test
    fun `ordinary apps remain allowed`() {
        val policy = policy()
        listOf(
            "com.spotify.music",
            "com.whatsapp",
            "org.telegram.messenger",
            "ru.yandex.searchplugin",
            "com.google.android.apps.maps",
            "com.instagram.android",
            "com.android.chrome"
        ).forEach { pkg ->
            val decision = policy.decidePackage(pkg)
            assertTrue("ожидали Allowed для $pkg, получили $decision", decision is PolicyDecision.Allowed)
        }
    }

}
