package com.jarvis.assistant.agent.tools.communication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
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
 * SMS Tool v0.2.
 *
 * Опасное действие (DangerLevel.HIGH): отправка сообщения от имени пользователя
 * необратима, поэтому требует подтверждения через ToolPermissionManager.
 *
 * Проверки разрешений централизованы в [DeviceCapabilityRegistry]:
 *  - SEND_SMS есть      → сообщение реально отправляется (SUCCESS);
 *  - SEND_SMS нет       → PERMISSION_REQUIRED, чтобы вызывающий слой запросил
 *                         разрешение и повторил вызов;
 *  - контакт не найден  → честный отказ вместо отправки «в никуда».
 */
@Singleton
class SmsTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: DeviceCapabilityRegistry,
    private val contactResolver: ContactResolver
) : CapabilityAwareTool {

    override val toolId: String = "communication.sms"
    override val description: String = "Отправляет SMS-сообщение контакту по имени или номеру телефона"
    override val category: ToolCategory = ToolCategory.COMMUNICATION
    override val riskLevel: ToolRisk = ToolRisk.CONFIRMATION_REQUIRED
    override val isOffline: Boolean = true
    override val requiresForeground: Boolean = true

    override val capabilityContract = ToolCapabilityContract(
        capabilities = setOf(
            DeviceCapability.SEND_SMS_DIRECTLY,
            DeviceCapability.OPEN_SMS_COMPOSER,
            DeviceCapability.READ_CONTACTS
        ),
        requiredPermissions = listOf(Manifest.permission.SEND_SMS),
        dangerLevel = DangerLevel.HIGH,
        confirmationRequired = true
    )

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("recipient") {
                put("type", "string")
                put("description", "Имя контакта или номер телефона")
            }
            putJsonObject("message") {
                put("type", "string")
                put("description", "Текст сообщения для отправки")
            }
        }
        put("required", buildJsonArray {
            add("recipient")
            add("message")
        })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val recipient = arguments["recipient"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val message = arguments["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        if (recipient.isEmpty()) return ToolExecutionResult.failure("Не указан получатель сообщения", "MISSING_RECIPIENT")
        if (message.isEmpty()) return ToolExecutionResult.failure("Не указан текст сообщения", "MISSING_MESSAGE")

        // 1. Возможность отправки SMS вообще.
        when (val smsStatus = capabilities.statusOf(DeviceCapability.SEND_SMS_DIRECTLY)) {
            is CapabilityStatus.Unsupported -> return ToolExecutionResult.unsupported(
                summary = smsStatus.reason,
                reason = "SMS_UNSUPPORTED"
            )
            is CapabilityStatus.PermissionRequired -> return ToolExecutionResult.permissionRequired(
                summary = "Чтобы отправить SMS для $recipient, нужно разрешение на отправку сообщений",
                permissions = smsStatus.permissions,
                data = buildJsonObject {
                    put("recipient", recipient)
                    put("message_preview", message.take(50))
                }
            )
            else -> Unit
        }

        // 2. Определение номера.
        val resolution = contactResolver.resolve(recipient)
        val phoneNumber = when (resolution) {
            is ContactResolution.Resolved -> resolution.phoneNumber
            is ContactResolution.PermissionRequired -> return ToolExecutionResult.permissionRequired(
                summary = "Чтобы найти номер контакта «$recipient», нужен доступ к контактам",
                permissions = resolution.permissions
            )
            is ContactResolution.NotFound -> return ToolExecutionResult.failure(
                summary = "Контакт «$recipient» не найден в телефонной книге, сообщение не отправлено",
                error = "CONTACT_NOT_FOUND"
            )
        }

        // 3. Реальная отправка.
        return try {
            val smsManager = smsManager()
                ?: return ToolExecutionResult.unsupported(
                    summary = "Служба SMS недоступна на этом устройстве",
                    reason = "SMS_MANAGER_UNAVAILABLE"
                )

            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }

            ToolExecutionResult.success(
                summary = "SMS отправлено для $recipient",
                data = buildJsonObject {
                    put("recipient", recipient)
                    put("phone_number", phoneNumber)
                    put("message_preview", message.take(50))
                    put("parts", parts.size)
                }
            )
        } catch (e: SecurityException) {
            ToolExecutionResult.permissionRequired(
                summary = "Система отклонила отправку SMS: нет разрешения SEND_SMS",
                permissions = listOf(Manifest.permission.SEND_SMS)
            )
        } catch (e: IllegalArgumentException) {
            ToolExecutionResult.failure(
                summary = "Не удалось отправить SMS: некорректный номер или пустое сообщение",
                error = "SMS_INVALID_ARGUMENT"
            )
        }
    }

    /**
     * Открывает SMS-приложение с подготовленным текстом. Используется как
     * альтернативный путь, когда пользователь не хочет выдавать SEND_SMS.
     */
    fun openComposer(phoneNumber: String, message: String): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
}
