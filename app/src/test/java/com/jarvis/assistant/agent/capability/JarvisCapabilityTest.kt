package com.jarvis.assistant.agent.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт Android Capability Layer: состав групп, целостность дерева
 * и семантика агрегации статусов.
 */
class JarvisCapabilityTest {

    @Test
    fun `layer exposes exactly the ten android capability groups in spec order`() {
        assertEquals(
            listOf(
                "device.bluetooth",
                "device.wifi",
                "device.brightness",
                "device.screenshot",
                "device.apps",
                "communication.sms",
                "communication.call",
                "media",
                "accessibility",
                "location"
            ),
            JarvisCapability.all.map { it.id }
        )
    }

    @Test
    fun `group ids are unique`() {
        val ids = JarvisCapability.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `byId resolves every group and unknown ids resolve to null`() {
        JarvisCapability.all.forEach { group ->
            assertSame(group, JarvisCapability.byId(group.id))
        }
        assertNull(JarvisCapability.byId("device.unknown"))
        assertNull(JarvisCapability.byId(""))
    }

    @Test
    fun `every leaf capability belongs to exactly one group`() {
        val assignments = JarvisCapability.all.flatMap { group ->
            group.leaves.map { leaf -> leaf to group.id }
        }
        val byLeaf = assignments.groupBy { it.first }
        byLeaf.forEach { (leaf, groups) ->
            assertEquals(
                "Leaf $leaf belongs to multiple groups: ${groups.map { it.second }}",
                1,
                groups.size
            )
        }
    }

    @Test
    fun `group references only existing leaf capabilities`() {
        val knownLeaves = DeviceCapability.entries.toSet()
        JarvisCapability.all.forEach { group ->
            assertTrue("${group.id} references unknown leaves", knownLeaves.containsAll(group.leaves))
        }
    }

    // ------------------------------------------------------------------ агрегация

    @Test
    fun `aggregation - any available leaf makes the group available`() {
        val status = aggregateCapabilityStatus(
            listOf(
                CapabilityStatus.Unsupported("нет на этом устройстве"),
                CapabilityStatus.Available,
                CapabilityStatus.PermissionRequired(listOf("android.permission.A"))
            )
        )
        assertTrue(status is CapabilityStatus.Available)
    }

    @Test
    fun `aggregation - all leaves unsupported keeps the first reason`() {
        val status = aggregateCapabilityStatus(
            listOf(
                CapabilityStatus.Unsupported("Требуется Android 11"),
                CapabilityStatus.Unsupported("Нет вспышки")
            )
        )
        assertTrue(status is CapabilityStatus.Unsupported)
        assertEquals("Требуется Android 11", (status as CapabilityStatus.Unsupported).reason)
    }

    @Test
    fun `aggregation - permission blockers are merged when no available path`() {
        val status = aggregateCapabilityStatus(
            listOf(
                CapabilityStatus.PermissionRequired(listOf("android.permission.A")),
                CapabilityStatus.UserActionRequired("Откройте настройки"),
                CapabilityStatus.PermissionRequired(listOf("android.permission.B", "android.permission.A"))
            )
        )
        assertTrue(status is CapabilityStatus.PermissionRequired)
        assertEquals(
            listOf("android.permission.A", "android.permission.B"),
            (status as CapabilityStatus.PermissionRequired).permissions
        )
    }

    @Test
    fun `aggregation - user action blocker wins over unsupported`() {
        val status = aggregateCapabilityStatus(
            listOf(
                CapabilityStatus.UserActionRequired("Включите службу специальных возможностей"),
                CapabilityStatus.Unsupported("Нет Bluetooth-адаптера")
            )
        )
        assertTrue(status is CapabilityStatus.UserActionRequired)
    }

    @Test
    fun `aggregation - empty group is unsupported`() {
        assertTrue(aggregateCapabilityStatus(emptyList()) is CapabilityStatus.Unsupported)
    }

    @Test
    fun `aggregation - screenshot group is available while media projection needs consent`() {
        // TAKE_SCREENSHOT_ACCESSIBILITY доступен → группа Available,
        // даже если MediaProjection требует согласия пользователя.
        val status = aggregateCapabilityStatus(
            listOf(
                CapabilityStatus.Available,
                CapabilityStatus.UserActionRequired("MediaProjection требует согласия")
            )
        )
        assertTrue(status is CapabilityStatus.Available)
    }

    @Test
    fun `fake registry aggregates group status over leaves`() {
        val fake = FakeCapabilityRegistry.create()
            .set(
                DeviceCapability.USE_ACCESSIBILITY_SERVICE,
                CapabilityStatus.UserActionRequired("Служба не включена")
            )

        assertTrue(fake.statusOf(JarvisCapability.Accessibility) is CapabilityStatus.UserActionRequired)

        // Разрешили службу на уровне листа — группа становится Available.
        val fakeOk = FakeCapabilityRegistry.create()
        assertTrue(fakeOk.statusOf(JarvisCapability.Accessibility) is CapabilityStatus.Available)
    }

    @Test
    fun `bluetooth group aggregation reflects android 13 restriction`() {
        val fake = FakeCapabilityRegistry.create().set(
            DeviceCapability.TOGGLE_BLUETOOTH_DIRECTLY,
            CapabilityStatus.UserActionRequired("Android 13+ запрещает программное переключение")
        )
        // READ_BLUETOOTH_STATE и OPEN_BLUETOOTH_SETTINGS доступны → группа Available.
        assertTrue(fake.statusOf(JarvisCapability.Bluetooth) is CapabilityStatus.Available)
    }
}
