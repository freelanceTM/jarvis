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
    data class Ambiguous(val candidates: List<Pair<String, String>>) : ContactResolution
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
            val normalized = normalizeNumber(raw)
            return if (isValidNormalizedNumber(normalized)) {
                ContactResolution.Resolved(normalized, null)
            } else {
                ContactResolution.NotFound(raw)
            }
        }

        // 2. Иначе нужен доступ к телефонной книге.
        if (!capabilities.statusOf(DeviceCapability.READ_CONTACTS).isAvailable) {
            return ContactResolution.PermissionRequired(listOf(Manifest.permission.READ_CONTACTS))
        }

        return queryContact(raw)
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

    private fun queryContact(name: String): ContactResolution {
        val matches = mutableListOf<Pair<String, String>>()
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
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            while (cursor.moveToNext() && matches.size < 20) {
                if (numIdx == -1) continue
                val number = cursor.getString(numIdx)?.let(::normalizeNumber).orEmpty()
                if (number.isBlank()) continue
                val displayName = if (nameIdx != -1) cursor.getString(nameIdx).orEmpty() else ""
                matches += displayName to number
            }
        }

        val distinct = matches.distinctBy { it.second }
        if (distinct.isEmpty()) return ContactResolution.NotFound(name)

        // Точное имя безопасно приоритетнее частичного LIKE, но два разных
        // номера с тем же именем всё равно требуют выбора пользователя.
        val exact = distinct.filter { it.first.equals(name, ignoreCase = true) }
        val candidates = if (exact.isNotEmpty()) exact else distinct
        return if (candidates.size == 1) {
            val (displayName, number) = candidates.single()
            ContactResolution.Resolved(number, displayName.takeIf { it.isNotBlank() })
        } else {
            ContactResolution.Ambiguous(candidates.take(3))
        }
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

        fun isValidNormalizedNumber(value: String): Boolean =
            Regex("^\\+?[0-9]{4,15}$").matches(value)
    }
}
