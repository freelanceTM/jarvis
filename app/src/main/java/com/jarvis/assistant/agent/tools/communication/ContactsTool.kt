package com.jarvis.assistant.agent.tools.communication

import android.content.Context
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
class ContactsTool @Inject constructor(
    @ApplicationContext private val context: Context
) : JarvisTool {

    override val toolId: String = "communication.contacts"
    override val description: String = "Ищет номер телефона и контакты в телефонной книге устройства"
    override val category: ToolCategory = ToolCategory.COMMUNICATION
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") {
                put("type", "string")
                put("description", "Имя контакта для поиска")
            }
        }
        put("required", buildJsonArray { add("name") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val searchName = arguments["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (searchName.isEmpty()) {
            return ToolExecutionResult.failure("Укажите имя для поиска", "MISSING_NAME")
        }

        return try {
            val cr = context.contentResolver
            val cursor = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$searchName%"),
                null
            )

            val contactsFound = mutableListOf<String>()
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext() && contactsFound.size < 3) {
                    if (nameIdx != -1 && numIdx != -1) {
                        val name = it.getString(nameIdx)
                        val num = it.getString(numIdx)
                        contactsFound.add("$name: $num")
                    }
                }
            }

            if (contactsFound.isNotEmpty()) {
                val summary = contactsFound.joinToString("; ")
                ToolExecutionResult.success("Найдено: $summary")
            } else {
                ToolExecutionResult.success("Контакт '$searchName' не найден в телефонной книге")
            }
        } catch (e: Exception) {
            ToolExecutionResult.failure("Ошибка доступа к контактам: ${e.localizedMessage}", "CONTACTS_ERROR")
        }
    }
}
