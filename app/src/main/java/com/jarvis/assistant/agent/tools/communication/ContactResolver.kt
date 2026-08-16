package com.jarvis.assistant.agent.tools.communication

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import com.jarvis.assistant.agent.capability.DeviceCapability
import com.jarvis.assistant.agent.capability.DeviceCapabilityRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Результат разрешения получателя в конкретный номер телефона.
 *
 * Раньше CallTool и SmsTool дублировали логику поиска контакта и в случае
 * неудачи молча подставляли введённую строку как «номер». Это приводило к
 * попыткам звонить на текст вроде "маме". Теперь неудача — явное состояние.
 */
sealed interface ContactResolution {
    data class Resolved(val phoneNumber: String, val displayName: String?) : ContactResolution
    data class PermissionRequired(val permissions: List<String>) : ContactResolution
    data class NotFound(val query: String) : ContactResolution
}

/**
 * Единая точка разрешения «имя контакта или номер» → номер телефона.
 */
@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: DeviceCapabilityRegistry
) {

    fun resolve(recipient: String): ContactResolution {
        val raw = recipient.trim()
        if (raw.isEmpty()) return ContactResolution.NotFound(recipient)

        // 1. Уже похоже на телефонный номер.
        if (looksLikePhoneNumber(raw)) {
            return ContactResolution.Resolved(normalizeNumber(raw), null)
        }

        // 2. Иначе нужен доступ к телефонной книге.
        if (!capabilities.statusOf(DeviceCapability.READ_CONTACTS).isAvailable) {
            return ContactResolution.PermissionRequired(listOf(Manifest.permission.READ_CONTACTS))
        }

        val match = queryContact(raw)
        return match ?: ContactResolution.NotFound(raw)
    }

    /** Возвращает до [limit] совпадений — используется ContactsTool. */
    fun search(query: String, limit: Int = 3): List<Pair<String, String>> {
        if (!capabilities.statusOf(DeviceCapability.READ_CONTACTS).isAvailable) return emptyList()
        val results = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && results.size < limit) {
                if (nameIdx != -1 && numIdx != -1) {
                    results.add(cursor.getString(nameIdx) to cursor.getString(numIdx))
                }
            }
        }
        return results
    }

    private fun queryContact(name: String): ContactResolution.Resolved? {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                if (numIdx != -1) {
                    val number = cursor.getString(numIdx)
                    val displayName = if (nameIdx != -1) cursor.getString(nameIdx) else null
                    if (!number.isNullOrBlank()) {
                        return ContactResolution.Resolved(normalizeNumber(number), displayName)
                    }
                }
            }
        }
        return null
    }

    companion object {
        /**
         * Номер должен состоять преимущественно из цифр. Строка "маме" или
         * "Иван" номером не является — раньше такие значения попадали в tel:.
         */
        fun looksLikePhoneNumber(value: String): Boolean {
            val digits = value.count { it.isDigit() }
            val allowed = value.all { it.isDigit() || it in " +-()\u00A0" }
            return digits >= 4 && allowed
        }

        fun normalizeNumber(value: String): String = value.replace(Regex("[^0-9+]"), "")
    }
}
