package com.jarvis.assistant.agent.tools.communication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "communication.sms"
    override val description: String = "Отправляет SMS-сообщение контакту по имени или номеру телефона"
    override val category: ToolCategory = ToolCategory.COMMUNICATION
    override val riskLevel: ToolRisk = ToolRisk.CONFIRMATION_REQUIRED
    override val isOffline: Boolean = true
    override val requiresForeground: Boolean = true

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
        
        if (recipient.isEmpty()) {
            return ToolExecutionResult.failure("Не указан получатель сообщения", "MISSING_RECIPIENT")
        }
        if (message.isEmpty()) {
            return ToolExecutionResult.failure("Не указан текст сообщения", "MISSING_MESSAGE")
        }

        // Проверка разрешений
        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        // Определяем номер телефона
        val phoneNumber = if (recipient.any { it.isDigit() } && recipient.length >= 4) {
            recipient.replace(Regex("[^0-9+]"), "")
        } else {
            if (hasContactsPermission) {
                findContactNumber(recipient) ?: recipient
            } else {
                recipient
            }
        }

        val hasValidNumber = phoneNumber.any { it.isDigit() } && phoneNumber.length >= 4

        return try {
            if (hasSmsPermission && hasValidNumber) {
                // Есть разрешение и валидный номер — отправляем напрямую
                sendSmsDirect(phoneNumber, message)
                ToolExecutionResult.success(
                    summary = "SMS отправлено для $recipient",
                    data = buildJsonObject {
                        put("recipient", recipient)
                        put("phone_number", phoneNumber)
                        put("message_preview", message.take(50))
                        put("sent_directly", true)
                    }
                )
            } else {
                // Открываем приложение SMS с предзаполненным сообщением
                openSmsApp(phoneNumber, message)
                
                val summary = when {
                    !hasSmsPermission -> "Открываю SMS-приложение. Для автоматической отправки разрешите доступ к SMS в настройках."
                    !hasValidNumber && !hasContactsPermission -> "Открываю SMS-приложение. Для поиска по имени разрешите доступ к контактам."
                    else -> "Открываю SMS-приложение для $recipient"
                }
                
                ToolExecutionResult.success(
                    summary = summary,
                    actionRequiresUser = true,
                    data = buildJsonObject {
                        put("recipient", recipient)
                        put("phone_number", phoneNumber)
                        put("message_preview", message.take(50))
                        put("sent_directly", false)
                        put("needs_sms_permission", !hasSmsPermission)
                        put("needs_contacts_permission", !hasContactsPermission)
                    }
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure(
                "Не удалось отправить SMS: ${e.localizedMessage}", 
                "SMS_ERROR"
            )
        }
    }

    private fun sendSmsDirect(phoneNumber: String, message: String) {
        val smsManager = context.getSystemService(SmsManager::class.java)
            ?: SmsManager.getDefault()
        
        // Разбиваем длинные сообщения на части
        val parts = smsManager.divideMessage(message)
        if (parts.size == 1) {
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
        } else {
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
        }
    }

    private fun openSmsApp(phoneNumber: String, message: String) {
        val smsUri = Uri.parse("smsto:$phoneNumber")
        val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun findContactNumber(contactName: String): String? {
        return try {
            val cr = context.contentResolver
            val cursor = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$contactName%"),
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numIdx != -1) {
                        return it.getString(numIdx)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Возвращает список отсутствующих разрешений
     */
    fun getMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) 
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.SEND_SMS)
        }
        
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) 
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_CONTACTS)
        }
        
        return missing
    }
}
