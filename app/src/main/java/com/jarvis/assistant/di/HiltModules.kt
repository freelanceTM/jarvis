package com.jarvis.assistant.di

import android.content.Context
import androidx.room.Room
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.tools.device.*
import com.jarvis.assistant.agent.tools.system.*
import com.jarvis.assistant.ai.AIClient
import com.jarvis.assistant.ai.UniversalAIClient
import com.jarvis.assistant.core.constants.AppConstants
import com.jarvis.assistant.core.dispatcher.CoroutineDispatchers
import com.jarvis.assistant.core.dispatcher.DefaultCoroutineDispatchers
import com.jarvis.assistant.core.network.LiveNetworkMonitor
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.core.security.SecurityManager
import com.jarvis.assistant.core.security.SecurityManagerImpl
import com.jarvis.assistant.data.local.JarvisDatabase
import com.jarvis.assistant.data.local.dao.MessageDao
import com.jarvis.assistant.data.remote.api.OpenAiApiService
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
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
    abstract fun bindNetworkMonitor(impl: LiveNetworkMonitor): NetworkMonitor

    @Binds
    @Singleton
    abstract fun bindAIClient(impl: UniversalAIClient): AIClient

    @Binds
    @Singleton
    abstract fun bindWakeWordDetector(impl: AlisaStyleWakeWordEngine): WakeWordDetector

    @Multibinds
    abstract fun bindToolsSet(): Set<JarvisTool>

    // 10 Базовых инструментов системы Android
    @Binds
    @IntoSet
    abstract fun bindGetDeviceInfoTool(tool: GetDeviceInfoTool): JarvisTool

    @Binds
    @IntoSet
    abstract fun bindGetBatteryTool(tool: GetBatteryTool): JarvisTool

    @Binds
    @IntoSet
    abstract fun bindGetTimeTool(tool: GetTimeTool): JarvisTool

    @Binds
    @IntoSet
    abstract fun bindGetNetworkStatusTool(tool: GetNetworkStatusTool): JarvisTool

    @Binds
    @IntoSet
    abstract fun bindOpenAppTool(tool: OpenAppTool): JarvisTool

    @Binds
    @IntoSet
    abstract fun bindSetVolumeTool(tool: SetVolumeTool): JarvisTool

    @Binds
    @IntoSet
    abstract fun bindSetBrightnessTool(tool: SetBrightnessTool): JarvisTool

    @Binds
    @IntoSet
    abstract fun bindFlashlightTool(tool: FlashlightTool): JarvisTool

    @Binds
    @IntoSet
    abstract fun bindBluetoothTool(tool: BluetoothTool): JarvisTool

    @Binds
    @IntoSet
    abstract fun bindWifiTool(tool: WifiTool): JarvisTool
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
    fun provideMessageDao(database: JarvisDatabase): MessageDao {
        return database.messageDao()
    }
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
            level = HttpLoggingInterceptor.Level.BASIC
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

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        json: Json
    ): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(AppConstants.DEFAULT_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenAiApiService(retrofit: Retrofit): OpenAiApiService {
        return retrofit.create(OpenAiApiService::class.java)
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
