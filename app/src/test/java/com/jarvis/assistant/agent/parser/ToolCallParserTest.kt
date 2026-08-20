package com.jarvis.assistant.agent.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ToolCallParserTest {

    private val parser = ToolCallParser(Json { ignoreUnknownKeys = true })

    @Test
    fun `parses single call and supported aliases`() {
        val variants = listOf(
            """{"tool":"system.time","arguments":{"zone":"UTC"}}""",
            """{"toolId":"system.time","params":{"zone":"UTC"}}""",
            """{"name":"system.time","arguments":{"zone":"UTC"}}"""
        )
        for (raw in variants) {
            val call = parser.parse(raw).single()
            assertEquals("system.time", call.toolId)
            assertEquals("UTC", call.arguments["zone"].toString().trim('"'))
        }
    }

    @Test
    fun `parses tool calls inside prose and code fence`() {
        val raw = """
            I will use a tool.
            ```json
            {"tool_calls":[
              {"tool":"device.volume","arguments":{"percent":50}},
              {"name":"system.time","params":{}}
            ]}
            ```
            Done.
        """.trimIndent()

        assertEquals(listOf("device.volume", "system.time"), parser.parse(raw).map { it.toolId })
    }

    @Test
    fun `braces and escaped quotes inside strings do not corrupt extraction`() {
        val raw = """prefix {"tool":"communication.sms","arguments":{"message":"value } { and \"quote\""}} suffix"""
        val call = parser.parse(raw).single()
        assertEquals("communication.sms", call.toolId)
        assertEquals("value } { and \"quote\"", call.arguments["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `separate unrelated object does not get glued to later tool call`() {
        val raw = """metadata {"status":"thinking"} then {"tool":"system.battery","arguments":{}}"""
        assertEquals("system.battery", parser.parse(raw).single().toolId)
    }

    @Test
    fun `malformed entry is skipped while valid calls survive`() {
        val raw = """{"tool_calls":[42,{"tool":{"nested":true}},{"tool":"system.time","arguments":[]},{"tool":"system.battery","arguments":{}}]}"""
        val calls = parser.parse(raw)
        assertEquals(listOf("system.time", "system.battery"), calls.map { it.toolId })
        assertTrue(calls.first().arguments.isEmpty())
    }

    @Test
    fun `wrong shapes placeholders and blank ids are rejected`() {
        val invalid = listOf(
            "", "null", "[]", "{}", "{not json", "{\"tool\":\"\"}",
            "{\"tool\":\"tool_name\"}", "{\"tool_calls\":{}}"
        )
        invalid.forEach { raw -> assertTrue("raw=$raw", parser.parse(raw).isEmpty()) }
    }

    @Test
    fun `oversized output is rejected before parsing`() {
        val raw = "x".repeat(ToolCallParser.MAX_LLM_OUTPUT_CHARS + 1) +
            "{\"tool\":\"system.time\"}"
        assertTrue(parser.parse(raw).isEmpty())
    }

    @Test
    fun `random malformed input never escapes as exception`() {
        val random = Random(0x5EED)
        val alphabet = "{}[]\\\",: abcXYZ0123\n\r\t😀"
        repeat(1_000) {
            val size = random.nextInt(0, 2_000)
            val value = buildString(size) {
                repeat(size) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            parser.parse(value)
        }
    }
}
