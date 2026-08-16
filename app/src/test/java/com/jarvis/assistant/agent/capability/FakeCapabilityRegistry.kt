package com.jarvis.assistant.agent.capability

/**
 * Тестовая реализация [CapabilityChecker]: позволяет проверять поведение
 * агента при любых Android-ограничениях без эмулятора.
 */
class FakeCapabilityRegistry(
    private val statuses: MutableMap<DeviceCapability, CapabilityStatus> = mutableMapOf(),
    private val grantedPermissions: MutableSet<String> = mutableSetOf()
) : CapabilityChecker {

    override fun statusOf(capability: DeviceCapability): CapabilityStatus =
        statuses[capability] ?: CapabilityStatus.Available

    override fun statusOf(capability: JarvisCapability): CapabilityStatus =
        aggregateCapabilityStatus(capability.leaves.map { statusOf(it) })

    override fun missingPermissions(permissions: List<String>): List<String> =
        permissions.filterNot { it in grantedPermissions }

    fun set(capability: DeviceCapability, status: CapabilityStatus) = apply {
        statuses[capability] = status
    }

    fun grant(vararg permissions: String) = apply {
        grantedPermissions.addAll(permissions)
    }

    companion object {
        /** Всё доступно — удобная база для тестов, не связанных с ограничениями. */
        fun create(): FakeCapabilityRegistry = FakeCapabilityRegistry()
    }
}
