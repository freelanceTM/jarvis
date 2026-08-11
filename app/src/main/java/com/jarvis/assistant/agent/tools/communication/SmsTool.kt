package com.jarvis.assistant.agent.tools.communication

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
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
    override val description: String = "Отправляет или подготавливает SMS-сообщение контакту"
    override val category: ToolCategory = ToolCategory.COMMUNICATION
    override val riskLevel: ToolRisk = ToolRisk.CONFIRMATION_REQUIRED
    override val isOffline: Boolean = true
    override val requiresForeground: Boolean = true

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

        val phoneNumber = if (recipient.any { it.isDigit() }) recipient else findContactNumber(recipient) ?: recipient

        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phoneNumber")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolExecutionResult.success("Открываю SMS для $recipient: \"$message\"", actionRequiresUser = true)
        } catch (e: Exception) {
            ToolExecutionResult.failure("Не удалось открыть SMS: ${e.localizedMessage}", "SMS_ERROR")
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
