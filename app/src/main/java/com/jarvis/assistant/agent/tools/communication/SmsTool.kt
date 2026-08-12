package com.jarvis.assistant.agent.tools.communication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
    override val description: String = "Отправляет SMS-сообщение контакту в фоновом режиме или открывает окно отправки"
    override val category: ToolCategory = ToolCategory.COMMUNICATION
    override val riskLevel: ToolRisk = ToolRisk.CONFIRMATION_REQUIRED
    override val isOffline: Boolean = true
    override val requiresForeground: Boolean = false

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("recipient") {
                put("type", "string")
                put("description", "Имя контакта или номер получателя")
            }
            putJsonObject("message") {
                put("type", "string")
                put("description", "Текст сообщения")
            }
        }
        put("required", buildJsonArray { add("recipient"); add("message") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val recipient = arguments["recipient"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val message = arguments["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        if (recipient.isEmpty() || message.isEmpty()) {
            return ToolExecutionResult.failure("Не указан получатель или текст SMS", "MISSING_PARAMS")
        }

        val phoneNumber = if (recipient.any { it.isDigit() } && recipient.length >= 4) {
            recipient.replace(Regex("[^0-9+]"), "")
        } else {
            findContactNumber(recipient) ?: recipient
        }

        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            if (hasSmsPermission && phoneNumber.any { it.isDigit() }) {
                // Прямая фоновая отправка SMS без экрана (Android 12+ getSystemService)
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                ToolExecutionResult.success("SMS для $recipient ($phoneNumber) успешно отправлено, сэр.")
            } else {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:$phoneNumber")
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolExecutionResult.success("Открываю SMS для $recipient: \"$message\"", actionRequiresUser = true)
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure("Не удалось отправить SMS: ${e.localizedMessage}", "SMS_ERROR")
        }
    }

    private fun findContactNumber(contactName: String): String? {
        val cr = context.contentResolver
        val cursor = cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$contactName%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (idx != -1) return it.getString(idx)
            }
        }
        return null
    }
}
