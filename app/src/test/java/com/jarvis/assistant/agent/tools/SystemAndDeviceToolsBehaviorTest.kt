package com.jarvis.assistant.agent.tools

import android.content.Context
import android.content.Intent
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
        every { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 4
        every { audio.setStreamVolume(any(), any(), any()) } just runs
        val tool = SetVolumeTool(context)

        val result = tool.execute(buildJsonObject { put("action", "set"); put("percent", 40) })
        val rolledBack = tool.rollback(JsonObject(emptyMap()), result.rollbackData)

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        verify { audio.setStreamVolume(AudioManager.STREAM_MUSIC, 6, AudioManager.FLAG_SHOW_UI) }
        verify { audio.setStreamVolume(AudioManager.STREAM_MUSIC, 4, AudioManager.FLAG_SHOW_UI) }
        assertTrue(rolledBack)
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
    fun `flashlight tool toggles real camera id and rolls back`() = runBlocking {
        val context = mockk<Context>()
        val camera = mockk<CameraManager>()
        every { context.getSystemService(Context.CAMERA_SERVICE) } returns camera
        every { camera.cameraIdList } returns arrayOf("rear")
        every { camera.setTorchMode(any(), any()) } just runs
        val tool = FlashlightTool(context)

        val result = tool.execute(buildJsonObject { put("enabled", true) })
        val rollback = tool.rollback(JsonObject(emptyMap()), result.rollbackData)

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        verify { camera.setTorchMode("rear", true) }
        verify { camera.setTorchMode("rear", false) }
        assertTrue(rollback)
    }

    @Test
    fun `flashlight tool distinguishes absent camera and platform failure`() = runBlocking {
        val context = mockk<Context>()
        val camera = mockk<CameraManager>()
        every { context.getSystemService(Context.CAMERA_SERVICE) } returns camera
        every { camera.cameraIdList } returns emptyArray()
        assertEquals("NO_CAMERA_ID", FlashlightTool(context).execute(JsonObject(emptyMap())).error)

        every { camera.cameraIdList } returns arrayOf("rear")
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
