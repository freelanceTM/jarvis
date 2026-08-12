package com.jarvis.assistant.agent.tools.communication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
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
class CallTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "communication.call"
    override val description: String = "Совершает телефонный звонок контакту по имени или номеру телефона"
    override val category: ToolCategory = ToolCategory.COMMUNICATION
    override val riskLevel: ToolRisk = ToolRisk.CONFIRMATION_REQUIRED
    override val isOffline: Boolean = true
    override val requiresForeground: Boolean = true

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

        // Проверка разрешений
        val hasCallPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        // 1. Если переданы цифры -> прямой номер
        val phoneNumber = if (recipient.any { it.isDigit() } && recipient.length >= 4) {
            recipient.replace(Regex("[^0-9+]"), "")
        } else {
            // 2. Ищем номер по имени в контактах (если есть разрешение)
            if (hasContactsPermission) {
                findContactNumber(recipient) ?: recipient
            } else {
                // Нет разрешения на контакты — пробуем использовать имя напрямую
                recipient
            }
        }

        // Проверяем, есть ли у нас номер для звонка
        val hasValidNumber = phoneNumber.any { it.isDigit() } && phoneNumber.length >= 4
        
        return try {
            val intent = if (hasCallPermission && hasValidNumber) {
                // Есть разрешение и валидный номер — звоним напрямую
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
            } else {
                // Нет разрешения или нет номера — открываем dialer
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
            }.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            
            val summary = when {
                hasCallPermission && hasValidNumber -> "Звоню $recipient ($phoneNumber)"
                !hasCallPermission -> "Открываю номеронабиратель для $recipient. Для прямых звонков разрешите доступ к телефону в настройках."
                !hasValidNumber && !hasContactsPermission -> "Открываю номеронабиратель. Для поиска по имени разрешите доступ к контактам."
                else -> "Открываю номеронабиратель для $recipient"
            }
            
            ToolExecutionResult.success(
                summary = summary,
                actionRequiresUser = !hasCallPermission || !hasValidNumber,
                data = buildJsonObject {
                    put("recipient", recipient)
                    put("phone_number", phoneNumber)
                    put("direct_call", hasCallPermission && hasValidNumber)
                    put("needs_call_permission", !hasCallPermission)
                    put("needs_contacts_permission", !hasContactsPermission)
                }
            )
        } catch (e: Exception) {
            ToolExecutionResult.failure("Не удалось совершить звонок: ${e.localizedMessage}", "CALL_ERROR")
        }
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
     * Возвращает список отсутствующих разрешений для полноценной работы
     */
    fun getMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) 
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CALL_PHONE)
        }
        
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) 
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_CONTACTS)
        }
        
        return missing
    }
}
