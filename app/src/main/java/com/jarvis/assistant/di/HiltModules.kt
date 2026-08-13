package com.jarvis.assistant.di

import android.content.Context
import androidx.room.Room
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.agent.automation.dao.AutomationDao
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.memory.dao.*
import com.jarvis.assistant.agent.tools.accessibility.ScreenReaderTool
import com.jarvis.assistant.agent.tools.accessibility.UiClickTool
import com.jarvis.assistant.agent.tools.communication.*
import com.jarvis.assistant.agent.tools.device.*
import com.jarvis.assistant.agent.tools.intelligence.ForgetMemoryTool
import com.jarvis.assistant.agent.tools.intelligence.RecallMemoryTool
import com.jarvis.assistant.agent.tools.intelligence.RememberFactTool
import com.jarvis.assistant.agent.tools.intelligence.WebSearchTool
import com.jarvis.assistant.agent.tools.location.LocationNavigationTool
import com.jarvis.assistant.agent.tools.media.MediaControlTool
import com.jarvis.assistant.agent.tools.productivity.AlarmTimerTool
import com.jarvis.assistant.agent.tools.productivity.CalendarTool
import com.jarvis.assistant.agent.tools.productivity.ClipboardTool
import com.jarvis.assistant.agent.tools.productivity.CreateAutomationTool
import com.jarvis.assistant.agent.tools.productivity.EarBriefingTool
import com.jarvis.assistant.agent.tools.system.*
import com.jarvis.assistant.ai.AIClient
import com.jarvis.assistant.ai.UniversalAIClient
import com.jarvis.assistant.core.constants.AppConstants
import com.jarvis.assistant.core.dispatcher.CoroutineDispatchers
import com.jarvis.assistant.core.dispatcher.DefaultCoroutineDispatchers
import com.jarvis.assistant.core.license.LicenseManager
import com.jarvis.assistant.core.license.LicenseManagerImpl
import com.jarvis.assistant.core.network.LiveNetworkMonitor
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.core.security.SecurityManager
import com.jarvis.assistant.core.security.SecurityManagerImpl
import com.jarvis.assistant.data.local.JarvisDatabase
import com.jarvis.assistant.data.local.dao.MessageDao
import com.jarvis.assistant.data.remote.interceptor.AuthInterceptor
import com.jarvis.assistant.data.repository.AIRepositoryImpl
import com.jarvis.assistant.data.repository.MessageRepositoryImpl
import com.jarvis.assistant.data.repository.SettingsRepositoryImpl
import com.jarvis.assistant.domain.repository.AIRepository
import com.jarvis.assistant.domain.repository.MessageRepository
import com.jarvis.assistant.domain.repository.SettingsRepository
import com.jarvis.assistant.voice.wakeword.AlisaStyleWakeWordEngine
import com.jarvis.assistant.voice.wakeword.WakeWordDetector
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {
    @Provides
    @Singleton
    fun provideCoroutineDispatchers(): CoroutineDispatchers = DefaultCoroutineDispatchers()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityAndNetworkBindingModule {
    @Binds
    @Singleton
    abstract fun bindSecurityManager(impl: SecurityManagerImpl): SecurityManager

    @Binds
    @Singleton
    abstract fun bindLicenseManager(impl: LicenseManagerImpl): LicenseManager

    @Binds
    @Singleton
    abstract fun bindNetworkMonitor(impl: LiveNetworkMonitor): NetworkMonitor

    @Binds
    @Singleton
    abstract fun bindAIClient(impl: UniversalAIClient): AIClient

    @Binds
    @Singleton
    abstract fun bindWakeWordDetector(impl: AlisaStyleWakeWordEngine): WakeWordDetector

    @Multibinds
    abstract fun bindToolsSet(): Set<JarvisTool>

    // 1. Системные инструменты (System)
    @Binds @IntoSet abstract fun bindGetDeviceInfoTool(tool: GetDeviceInfoTool): JarvisTool
    @Binds @IntoSet abstract fun bindGetBatteryTool(tool: GetBatteryTool): JarvisTool
    @Binds @IntoSet abstract fun bindGetTimeTool(tool: GetTimeTool): JarvisTool
    @Binds @IntoSet abstract fun bindGetNetworkStatusTool(tool: GetNetworkStatusTool): JarvisTool

    // 2. Управление устройством (Device & Media)
    @Binds @IntoSet abstract fun bindOpenAppTool(tool: OpenAppTool): JarvisTool
    @Binds @IntoSet abstract fun bindSetVolumeTool(tool: SetVolumeTool): JarvisTool
    @Binds @IntoSet abstract fun bindSetBrightnessTool(tool: SetBrightnessTool): JarvisTool
    @Binds @IntoSet abstract fun bindFlashlightTool(tool: FlashlightTool): JarvisTool
    @Binds @IntoSet abstract fun bindBluetoothTool(tool: BluetoothTool): JarvisTool
    @Binds @IntoSet abstract fun bindWifiTool(tool: WifiTool): JarvisTool
    @Binds @IntoSet abstract fun bindDoNotDisturbTool(tool: DoNotDisturbTool): JarvisTool
    @Binds @IntoSet abstract fun bindScreenshotTool(tool: ScreenshotTool): JarvisTool
    @Binds @IntoSet abstract fun bindMediaControlTool(tool: MediaControlTool): JarvisTool

    // 3. Связь и коммуникации (Communication)
    @Binds @IntoSet abstract fun bindCallTool(tool: CallTool): JarvisTool
    @Binds @IntoSet abstract fun bindSmsTool(tool: SmsTool): JarvisTool
    @Binds @IntoSet abstract fun bindContactsTool(tool: ContactsTool): JarvisTool
    @Binds @IntoSet abstract fun bindShareTool(tool: ShareTool): JarvisTool

    // 4. Продуктивность и задачи (Productivity)
    @Binds @IntoSet abstract fun bindAlarmTimerTool(tool: AlarmTimerTool): JarvisTool
    @Binds @IntoSet abstract fun bindCalendarTool(tool: CalendarTool): JarvisTool
    @Binds @IntoSet abstract fun bindClipboardTool(tool: ClipboardTool): JarvisTool
    @Binds @IntoSet abstract fun bindCreateAutomationTool(tool: CreateAutomationTool): JarvisTool
    @Binds @IntoSet abstract fun bindEarBriefingTool(tool: EarBriefingTool): JarvisTool

    // 5. Локация и навигация (Location)
    @Binds @IntoSet abstract fun bindLocationNavigationTool(tool: LocationNavigationTool): JarvisTool

    // 6. Интеллект, память и поиск (Intelligence & Memory 2.0)
    @Binds @IntoSet abstract fun bindRememberFactTool(tool: RememberFactTool): JarvisTool
    @Binds @IntoSet abstract fun bindRecallMemoryTool(tool: RecallMemoryTool): JarvisTool
    @Binds @IntoSet abstract fun bindForgetMemoryTool(tool: ForgetMemoryTool): JarvisTool
    @Binds @IntoSet abstract fun bindWebSearchTool(tool: WebSearchTool): JarvisTool

    // 7. Спец. возможности и UI управление (Accessibility)
    @Binds @IntoSet abstract fun bindScreenReaderTool(tool: ScreenReaderTool): JarvisTool
    @Binds @IntoSet abstract fun bindUiClickTool(tool: UiClickTool): JarvisTool
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideJarvisDatabase(
        @ApplicationContext context: Context
    ): JarvisDatabase {
        return Room.databaseBuilder(
            context,
            JarvisDatabase::class.java,
            AppConstants.DATABASE_NAME
        ).fallbackToDestructiveMigration()
         .build()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: JarvisDatabase): MessageDao = database.messageDao()

    @Provides
    @Singleton
    fun provideMemoryDao(database: JarvisDatabase): MemoryDao = database.memoryDao()

    @Provides
    @Singleton
    fun provideFactDao(database: JarvisDatabase): FactDao = database.factDao()

    @Provides
    @Singleton
    fun providePreferenceDao(database: JarvisDatabase): PreferenceDao = database.preferenceDao()

    @Provides
    @Singleton
    fun provideProcedureDao(database: JarvisDatabase): ProcedureDao = database.procedureDao()

    @Provides
    @Singleton
    fun provideAutomationDao(database: JarvisDatabase): AutomationDao = database.automationDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
            redactHeader("x-goog-api-key")
            redactHeader("api-key")
            redactHeader("X-Api-Key")
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(AppConstants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AppConstants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(AppConstants.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds
    @Singleton
    abstract fun bindAIRepository(impl: AIRepositoryImpl): AIRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
