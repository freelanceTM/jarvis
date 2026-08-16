package com.jarvis.assistant.agent.tools.communication

import android.Manifest
import com.jarvis.assistant.agent.capability.CapabilityStatus
import com.jarvis.assistant.agent.capability.DangerLevel
import com.jarvis.assistant.agent.capability.DeviceCapability
import com.jarvis.assistant.agent.capability.DeviceCapabilityRegistry
import com.jarvis.assistant.agent.capability.ToolCapabilityContract
import com.jarvis.assistant.agent.core.CapabilityAwareTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsTool @Inject constructor(
    private val capabilities: DeviceCapabilityRegistry,
    private val contactResolver: ContactResolver
) : CapabilityAwareTool {

    override val toolId: String = "communication.contacts"
    override val description: String = "Ищет номер телефона и контакты в телефонной книге устройства"
    override val category: ToolCategory = ToolCategory.COMMUNICATION
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val capabilityContract = ToolCapabilityContract(
        capabilities = setOf(DeviceCapability.READ_CONTACTS),
        requiredPermissions = listOf(Manifest.permission.READ_CONTACTS),
        dangerLevel = DangerLevel.LOW
    )

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
        val searchName = arguments["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: arguments["query"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: ""

        if (searchName.isEmpty()) {
            return ToolExecutionResult.failure("Укажите имя для поиска", "MISSING_NAME")
        }

        val status = capabilities.statusOf(DeviceCapability.READ_CONTACTS)
        if (status is CapabilityStatus.PermissionRequired) {
            return ToolExecutionResult.permissionRequired(
                summary = "Чтобы искать контакты, нужен доступ к телефонной книге",
                permissions = status.permissions
            )
        }

        val matches = contactResolver.search(searchName)
        return if (matches.isNotEmpty()) {
            ToolExecutionResult.success(
                summary = "Найдено: " + matches.joinToString("; ") { "${it.first}: ${it.second}" },
                data = buildJsonObject {
                    put("query", searchName)
                    put("count", matches.size)
                    put("contacts", buildJsonArray {
                        matches.forEach { (name, number) ->
                            add(buildJsonObject {
                                put("name", name)
                                put("number", number)
                            })
                        }
                    })
                }
            )
        } else {
            // Пустой результат — это корректно выполненный поиск без совпадений.
            ToolExecutionResult.success(
                summary = "Контакт «$searchName» не найден в телефонной книге",
                data = buildJsonObject {
                    put("query", searchName)
                    put("count", 0)
                }
            )
        }
    }
}
