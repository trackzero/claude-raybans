package com.raybans.ha.ha

import android.util.Log
import com.raybans.ha.glasses.TtsPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "ClaudeVoiceAssist"
private const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
private const val MODEL = "claude-haiku-4-5-20251001"
private const val MAX_TOKENS = 1024
private val JSON_MT = "application/json".toMediaType()

/**
 * Processes voice queries via Claude with HA MCP tools.
 *
 * Flow:
 *  1. Load MCP tools from [McpClient.listTools]
 *  2. Send user query to Claude API with those tools
 *  3. If Claude returns tool_use blocks, execute each via [McpClient.callTool]
 *  4. Feed tool results back to Claude, repeat until end_turn
 *  5. Speak final response via [TtsPlayer]
 */
class ClaudeVoiceAssist(
    private val claudeApiKey: String,
    private val mcpClient: McpClient,
    private val ttsPlayer: TtsPlayer,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun processQuery(spokenText: String) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Processing query: $spokenText")
        try {
            val tools = mcpClient.listTools()
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", spokenText)
                })
            }

            val response = runToolLoop(messages, tools)
            Log.i(TAG, "Claude response: $response")
            ttsPlayer.speak(response)
        } catch (e: Exception) {
            Log.e(TAG, "Voice assist error", e)
            ttsPlayer.speak("Sorry, I couldn't connect to Home Assistant right now.")
        }
    }

    private fun runToolLoop(messages: JSONArray, tools: JSONArray): String {
        repeat(5) { // max 5 tool-call rounds
            val resp = callClaude(messages, tools)
            val stopReason = resp.optString("stop_reason")
            val content = resp.optJSONArray("content") ?: JSONArray()

            if (stopReason == "end_turn") {
                return extractText(content)
            }

            if (stopReason == "tool_use") {
                // Add assistant message with tool_use blocks
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", content)
                })

                // Execute each tool call and collect results
                val toolResults = JSONArray()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.optString("type") != "tool_use") continue

                    val toolName = block.getString("name")
                    val toolInput = block.optJSONObject("input") ?: JSONObject()
                    val toolUseId = block.getString("id")

                    Log.d(TAG, "Calling tool: $toolName($toolInput)")
                    val result = try {
                        mcpClient.callTool(toolName, toolInput)
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool call failed: $toolName", e)
                        "error: ${e.message}"
                    }
                    Log.d(TAG, "Tool result: $result")

                    toolResults.put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", toolUseId)
                        put("content", result)
                    })
                }

                // Add tool results as user message
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", toolResults)
                })
            } else {
                return extractText(content)
            }
        }
        return "Done."
    }

    private fun extractText(content: JSONArray): String {
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") {
                return block.optString("text", "Done.")
            }
        }
        return "Done."
    }

    private fun callClaude(messages: JSONArray, tools: JSONArray): JSONObject {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("system", "You control Home Assistant for the user. Use the provided tools to fulfill their requests. Keep verbal responses short — one or two sentences maximum, since they will be spoken aloud.")
            put("tools", tools)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(CLAUDE_API_URL)
            .addHeader("x-api-key", claudeApiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON_MT))
            .build()

        val response = http.newCall(request).execute()
        val responseBody = response.body?.string() ?: "{}"
        response.close()

        if (!response.isSuccessful) {
            Log.e(TAG, "Claude API error ${response.code}: $responseBody")
            throw Exception("Claude API error: ${response.code}")
        }
        return JSONObject(responseBody)
    }
}
