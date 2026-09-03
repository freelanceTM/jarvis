package com.jarvis.assistant.agent.tools

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.jarvis.assistant.agent.apps.AppResolution
import com.jarvis.assistant.agent.apps.AppResolver
import com.jarvis.assistant.agent.model.ToolExecutionStatus
import com.jarvis.assistant.agent.tools.device.FlashlightTool
import com.jarvis.assistant.agent.tools.device.OpenAppTool
import com.jarvis.assistant.agent.tools.device.SetVolumeTool
import com.jarvis.assistant.agent.tools.system.GetBatteryTool
import com.jarvis.assistant.agent.tools.system.GetNetworkStatusTool
import com.jarvis.assistant.agent.tools.system.GetTimeTool
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAndDeviceToolsBehaviorTest {
    @Test
    fun `time tool returns structured current values`() = runBlocking {
        val before = System.currentTimeMillis()
        val result = GetTimeTool().execute(JsonObject(emptyMap()))
        val after = System.currentTimeMillis()

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertTrue(result.data?.get("time")?.jsonPrimitive?.content.orEmpty().matches(Regex("\\d{2}:\\d{2}")))
        val timestamp = result.data?.get("timestamp")?.jsonPrimitive?.content?.toLong()
        assertNotNull(timestamp)
        assertTrue(timestamp!! in before..after)
    }

    @Test
    fun `battery tool calculates scaled percentage and charging state`() = runBlocking {
        val intent = mockk<Intent>()
        every { intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns 45
        every { intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns 60
        every { intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) } returns BatteryManager.BATTERY_STATUS_CHARGING
        val context = mockk<Context>()
        every { context.registerReceiver(null, any()) } returns intent

        val result = GetBatteryTool(context).execute(JsonObject(emptyMap()))

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals(75, result.data?.get("percent")?.jsonPrimitive?.int)
        assertTrue(result.data?.get("is_charging")?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `battery tool fails instead of fabricating one hundred percent when status is absent`() = runBlocking {
        val context = mockk<Context>()
        every { context.registerReceiver(null, any()) } returns null

        val result = GetBatteryTool(context).execute(JsonObject(emptyMap()))

        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals("BATTERY_STATUS_UNAVAILABLE", result.error)
    }

    @Test
    fun `network tool reports validated wifi and offline states`() = runBlocking {
        val context = mockk<Context>()
        val manager = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns manager
        every { manager.activeNetwork } returns network
        every { manager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns false
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) } returns false

        val online = GetNetworkStatusTool(context).execute(JsonObject(emptyMap()))
        assertEquals("Wi-Fi", online.data?.get("transport_type")?.jsonPrimitive?.content)

        every { manager.activeNetwork } returns null
        every { manager.getNetworkCapabilities(null) } returns null
        val offline = GetNetworkStatusTool(context).execute(JsonObject(emptyMap()))
        assertFalse(offline.data?.get("is_connected")?.jsonPrimitive?.boolean ?: true)
    }

    @Test
    fun `volume tool handles exact percentage and rollback`() = runBlocking {
        val context = mockk<Context>()
        val audio = mockk<AudioManager>()
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audio
        every { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) } returns 0
        // Read-back: 4 (до мутации) → 6 (система подтвердила set 40%) → 4 (подтверждение rollback)
        every { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } returnsMany listOf(4, 6, 4)
        every { audio.setStreamVolume(any(), any(), any()) } just runs
        val tool = SetVolumeTool(context)

        val result = tool.execute(buildJsonObject { put("action", "set"); put("percent", 40) })
        val rolledBack = tool.rollback(JsonObject(emptyMap()), result.rollbackData)

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        verify { audio.setStreamVolume(AudioManager.STREAM_MUSIC, 6, AudioManager.FLAG_SHOW_UI) }
        // Rollback применяет тишину (без FLAG_SHOW_UI) и подтверждается read-back'ом.
        verify { audio.setStreamVolume(AudioManager.STREAM_MUSIC, 4, 0) }
        assertTrue(rolledBack)
    }

    @Test
    fun `volume tool refuses success when system does not apply the change`() = runBlocking {
        val context = mockk<Context>()
        val audio = mockk<AudioManager>()
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audio
        every { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) } returns 0
        // Система «не применила» изменение: read-back всегда возвращает прежний уровень.
        every { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 4
        every { audio.adjustStreamVolume(any(), any(), any()) } just runs

        val result = SetVolumeTool(context).execute(buildJsonObject { put("action", "up") })

        // Fake Success запрещён: без подтверждённого изменения громкости — FAILURE.
        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals("VOLUME_UNCHANGED", result.error)
    }

    @Test
    fun `volume tool reports already at maximum instead of claiming increase`() = runBlocking {
        val context = mockk<Context>()
        val audio = mockk<AudioManager>()
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audio
        every { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) } returns 0
        every { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audio.adjustStreamVolume(any(), any(), any()) } just runs

        val result = SetVolumeTool(context).execute(buildJsonObject { put("action", "up") })

        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals("VOLUME_AT_LIMIT", result.error)
        assertTrue(result.summary.contains("максимуме"))
    }

    @Test
    fun `volume tool returns structured failures for missing service unknown action and platform error`() = runBlocking {
        val missingContext = mockk<Context>()
        every { missingContext.getSystemService(Context.AUDIO_SERVICE) } returns null
        assertEquals(
            "NO_AUDIO_SERVICE",
            SetVolumeTool(missingContext).execute(buildJsonObject { put("action", "up") }).error
        )

        val context = mockk<Context>()
        val audio = mockk<AudioManager>()
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audio
        every { audio.getStreamMaxVolume(any()) } returns 10
        every { audio.getStreamMinVolume(any()) } returns 0
        every { audio.getStreamVolume(any()) } returns 3
        val tool = SetVolumeTool(context)
        assertEquals(
            "UNKNOWN_ACTION",
            tool.execute(buildJsonObject { put("action", "impossible") }).error
        )

        every { audio.adjustStreamVolume(any(), any(), any()) } throws SecurityException("denied")
        assertEquals("AUDIO_ERROR", tool.execute(buildJsonObject { put("action", "up") }).error)
    }

    @Test
    fun `flashlight verifies torch state through system callback and picks flash camera`() = runBlocking {
        val context = mockk<Context>()
        val camera = mockk<CameraManager>()
        every { context.getSystemService(Context.CAMERA_SERVICE) } returns camera
        every { camera.cameraIdList } returns arrayOf("front", "rear")

        // «front» — без вспышки, «rear» — со вспышкой: камера выбирается по признаку.
        val frontChars = mockk<CameraCharacteristics>()
        val rearChars = mockk<CameraCharacteristics>()
        every { camera.getCameraCharacteristics("front") } returns frontChars
        every { camera.getCameraCharacteristics("rear") } returns rearChars
        every { frontChars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) } returns false
        every { frontChars.get(CameraCharacteristics.LENS_FACING) } returns CameraCharacteristics.LENS_FACING_FRONT
        every { rearChars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) } returns true
        every { rearChars.get(CameraCharacteristics.LENS_FACING) } returns CameraCharacteristics.LENS_FACING_BACK

        var torchOn = false
        var torchCallback: CameraManager.TorchCallback? = null
        every { camera.registerTorchCallback(any(), any()) } answers {
            torchCallback = firstArg<CameraManager.TorchCallback>()
            if (torchOn) torchCallback?.onTorchModeChanged("rear", true)
        }
        every { camera.unregisterTorchCallback(any()) } just runs
        every { camera.setTorchMode("rear", true) } answers {
            torchOn = true
            torchCallback?.onTorchModeChanged("rear", true)
        }
        every { camera.setTorchMode("rear", false) } answers {
            torchOn = false
            torchCallback?.onTorchModeChanged("rear", false)
        }
        val tool = FlashlightTool(context)

        val result = tool.execute(buildJsonObject { put("enabled", true) })

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertTrue(result.data?.get("verified")?.jsonPrimitive?.boolean == true)
        verify { camera.setTorchMode("rear", true) }

        val rolledBack = tool.rollback(JsonObject(emptyMap()), result.rollbackData)
        assertTrue(rolledBack)
        verify { camera.setTorchMode("rear", false) }
    }

    @Test
    fun `flashlight refuses success when system never confirms torch state`() = runBlocking {
        val context = mockk<Context>()
        val camera = mockk<CameraManager>()
        every { context.getSystemService(Context.CAMERA_SERVICE) } returns camera
        every { camera.cameraIdList } returns arrayOf("rear")
        val rearChars = mockk<CameraCharacteristics>()
        every { camera.getCameraCharacteristics("rear") } returns rearChars
        every { rearChars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) } returns true
        every { rearChars.get(CameraCharacteristics.LENS_FACING) } returns CameraCharacteristics.LENS_FACING_BACK
        every { camera.registerTorchCallback(any(), any()) } just runs
        every { camera.unregisterTorchCallback(any()) } just runs
        every { camera.setTorchMode(any(), any()) } just runs

        val result = FlashlightTool(context).execute(buildJsonObject { put("enabled", true) })

        // Callback не подтвердил переход вспышки — SUCCESS запрещён.
        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals("TORCH_VERIFY_FAILED", result.error)
        verify { camera.setTorchMode("rear", true) }
    }

    @Test
    fun `flashlight tool distinguishes absent camera and platform failure`() = runBlocking {
        val context = mockk<Context>()
        val camera = mockk<CameraManager>()
        every { context.getSystemService(Context.CAMERA_SERVICE) } returns camera
        every { camera.cameraIdList } returns emptyArray()
        assertEquals("NO_CAMERA_ID", FlashlightTool(context).execute(JsonObject(emptyMap())).error)

        every { camera.cameraIdList } returns arrayOf("rear")
        val rearChars = mockk<CameraCharacteristics>()
        every { camera.getCameraCharacteristics("rear") } returns rearChars
        every { rearChars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) } returns true
        every { rearChars.get(CameraCharacteristics.LENS_FACING) } returns CameraCharacteristics.LENS_FACING_BACK
        every { camera.registerTorchCallback(any(), any()) } just runs
        every { camera.unregisterTorchCallback(any()) } just runs
        every { camera.setTorchMode("rear", any()) } throws SecurityException("denied")
        assertEquals("TORCH_ERROR", FlashlightTool(context).execute(JsonObject(emptyMap())).error)
    }

    @Test
    fun `open app tool reports missing unknown ambiguous and not-installed outcomes without launching`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val resolver = mockk<AppResolver>()
        val tool = OpenAppTool(context, resolver)

        assertEquals("MISSING_PARAM", tool.execute(JsonObject(emptyMap())).error)

        every { resolver.resolve("ghost") } returns AppResolution.Unknown("ghost")
        assertEquals("APP_UNKNOWN", tool.execute(buildJsonObject { put("app_name", "ghost") }).error)

        every { resolver.resolve("telegram") } returns AppResolution.NotInstalled("telegram", "org.telegram.messenger")
        val absent = tool.execute(buildJsonObject { put("app_name", "telegram") })
        assertEquals("APP_NOT_INSTALLED", absent.error)
        assertEquals("org.telegram.messenger", absent.data?.get("expected_package")?.jsonPrimitive?.content)

        every { resolver.resolve("map") } returns AppResolution.Ambiguous(
            "map",
            listOf(
                AppResolution.Resolved("maps.one", "Map One", AppResolution.MatchKind.FUZZY),
                AppResolution.Resolved("maps.two", "Map Two", AppResolution.MatchKind.FUZZY)
            )
        )
        val ambiguous = tool.execute(buildJsonObject { put("app_name", "map") })
        assertEquals("AMBIGUOUS_APP", ambiguous.error)
        assertTrue(ambiguous.summary.contains("Map One"))
    }
}
