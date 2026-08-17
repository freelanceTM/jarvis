package com.jarvis.assistant.agent.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.jarvis.assistant.agent.memory.semantic.SemanticTextMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Разрешение «как пользователь назвал приложение» → package name.
 *
 * Android-обвязка над чистым каскадом [AppMatchCascade]:
 *
 *   exact match → normalized match → alias match → fuzzy match → semantic match
 *
 * Каскад работает ТОЛЬКО по списку установленных приложений
 * ([installedApps]) — несуществующее приложение никогда не открывается.
 */
@Singleton
class AppResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    semanticMatcher: SemanticTextMatcher
) {
    private val cascade = AppMatchCascade(semanticMatcher)

    /** Разговорные алиасы (делегирование в каскад). */
    val aliases: Map<String, List<String>> get() = cascade.aliases

    /** Системные действия без launch intent (настройки, камера). */
    val systemActions: Map<String, String> get() = cascade.systemActions

    fun resolve(rawQuery: String): AppResolution =
        cascade.resolve(installedApps(), rawQuery)

    /** Приложения, которые реально можно запустить (есть launch intent). */
    fun installedApps(): List<InstalledApp> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(launcherIntent, 0).mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(pm)?.toString() ?: pkg
                InstalledApp(pkg, label)
            }.distinctBy { it.packageName }
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0).let { true }
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
