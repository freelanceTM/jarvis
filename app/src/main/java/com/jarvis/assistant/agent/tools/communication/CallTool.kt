package com.jarvis.assistant.agent.tools.communication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.agent.capability.CapabilityStatus
import com.jarvis.assistant.agent.capability.DangerLevel
import com.jarvis.assistant.agent.capability.DeviceCapability
import com.jarvis.assistant.agent.capability.DeviceCapabilityRegistry
import com.jarvis.assistant.agent.capability.ToolCapabilityContract
import com.jarvis.assistant.agent.core.CapabilityAwareTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Call Tool v0.2.
 *
 * Разница между двумя путями теперь отражена честно:
 *  - CALL_PHONE выдано → ACTION_CALL, вызов действительно начинается (SUCCESS);
 *  - разрешения нет    → ACTION_DIAL открывает номеронабиратель с введённым
 *                        номером, но звонок НЕ совершён. Это USER_ACTION_REQUIRED,
 *                        а не SUCCESS: пользователь должен нажать кнопку вызова.
 */
@Singleton
class CallTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: DeviceCapabilityRegistry,
    private val contactResolver: ContactResolver
) : CapabilityAwareTool {

    override val toolId: String = "communication.call"
    override val description: String = "Совершает телефонный звонок контакту по имени или номеру телефона"
    override val category: ToolCategory = ToolCategory.COMMUNICATION
    override val riskLevel: ToolRisk = ToolRisk.CONFIRMATION_REQUIRED
    override val isOffline: Boolean = true
    override val requiresForeground: Boolean = true

    override val capabilityContract = ToolCapabilityContract(
        capabilities = setOf(
            DeviceCapability.PLACE_CALL_DIRECTLY,
            DeviceCapability.OPEN_DIALER,
            DeviceCapability.READ_CONTACTS
        ),
        requiredPermissions = listOf(Manifest.permission.CALL_PHONE),
        dangerLevel = DangerLevel.MEDIUM,
        confirmationRequired = true
    )

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("recipient") {
                put("type", "string")
                put("description", "Имя контакта из телефонной книги или номер телефона (например: Иван, +79991234567)")
            }
        }
        put("required", buildJsonArray { add("recipient") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val recipient = arguments["recipient"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (recipient.isEmpty()) {
            return ToolExecutionResult.failure("Не указан номер или имя контакта", "MISSING_RECIPIENT")
        }

        if (capabilities.statusOf(DeviceCapability.OPEN_DIALER) is CapabilityStatus.Unsupported) {
            return ToolExecutionResult.unsupported(
                summary = "Это устройство не поддерживает телефонные вызовы",
                reason = "TELEPHONY_UNSUPPORTED"
            )
        }

        // 1. Определяем реальный номер.
        val phoneNumber = when (val resolution = contactResolver.resolve(recipient)) {
            is ContactResolution.Resolved -> resolution.phoneNumber
            is ContactResolution.PermissionRequired -> return ToolExecutionResult.permissionRequired(
                summary = "Чтобы найти номер контакта «$recipient», нужен доступ к контактам",
                permissions = resolution.permissions
            )
            is ContactResolution.NotFound -> return ToolExecutionResult.failure(
                summary = "Контакт «$recipient» не найден в телефонной книге, звонок не выполнен",
                error = "CONTACT_NOT_FOUND"
            )
        }

        val canCallDirectly = capabilities.statusOf(DeviceCapability.PLACE_CALL_DIRECTLY).isAvailable

        // 2. Прямой вызов при наличии разрешения.
        if (canCallDirectly) {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                ToolExecutionResult.success(
                    summary = "Звоню $recipient ($phoneNumber)",
                    data = buildJsonObject {
                        put("recipient", recipient)
                        put("phone_number", phoneNumber)
                        put("direct_call", true)
                    }
                )
            } catch (e: SecurityException) {
                ToolExecutionResult.permissionRequired(
                    summary = "Система отклонила вызов: нет разрешения CALL_PHONE",
                    permissions = listOf(Manifest.permission.CALL_PHONE)
                )
            } catch (e: android.content.ActivityNotFoundException) {
                ToolExecutionResult.failure(
                    summary = "На устройстве нет приложения для звонков",
                    error = "NO_DIALER_APP"
                )
            }
        }

        // 3. Без разрешения открываем номеронабиратель — но это НЕ состоявшийся звонок.
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(dialIntent)
            ToolExecutionResult.userActionRequired(
                summary = "Открыл номеронабиратель с номером $recipient. Нажмите кнопку вызова, сэр — для автоматических звонков нужно разрешение на телефон.",
                reason = "CALL_PHONE_PERMISSION_REQUIRED",
                data = buildJsonObject {
                    put("recipient", recipient)
                    put("phone_number", phoneNumber)
                    put("direct_call", false)
                    put("required_permission", Manifest.permission.CALL_PHONE)
                }
            )
        } catch (e: android.content.ActivityNotFoundException) {
            ToolExecutionResult.failure(
                summary = "На устройстве нет приложения для звонков",
                error = "NO_DIALER_APP"
            )
        }
    }
}
