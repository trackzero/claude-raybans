package com.raybans.ha.ha

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "McpClient"
private val JSON_MT = "application/json".toMediaType()

/**
 * Minimal MCP HTTP client for HA's /api/mcp endpoint.
 *
 * Uses the MCP Streamable HTTP transport (2024-11-05 protocol version).
 * All calls are synchronous — run on Dispatchers.IO.
 *
 * Flow:
 *  1. initialize() — MCP handshake, stores Mcp-Session-Id
 *  2. listTools()  — returns list of available HA tools (cached)
 *  3. callTool()   — executes a single tool and returns the result text
 */
class McpClient(
    private val mcpUrl: String,
    private val haToken: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val id = AtomicInteger(1)
    private var sessionId: String? = null
    private var initialized = false

    /** MCP tools converted to Claude's input_schema format. Cached after first fetch. */
    private var cachedTools: JSONArray? = null

    fun ensureInitialized() {
        if (initialized) return
        val resp = rpc("initialize", JSONObject().apply {
            put("protocolVersion", "2024-11-05")
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().apply {
                put("name", "raybans-bridge")
                put("version", "1.0")
            })
        })
        Log.i(TAG, "MCP initialized: ${resp.optJSONObject("result")?.optJSONObject("serverInfo")}")
        // Send notifications/initialized (no response expected)
        rpcNotify("notifications/initialized")
        initialized = true
    }

    /** Returns Claude-formatted tools (name, description, input_schema). Cached. */
    fun listTools(): JSONArray {
        cachedTools?.let { return it }
        ensureInitialized()
        val result = rpc("tools/list").optJSONObject("result")
        val mcpTools = result?.optJSONArray("tools") ?: JSONArray()
        val claudeTools = JSONArray()
        for (i in 0 until mcpTools.length()) {
            val t = mcpTools.getJSONObject(i)
            claudeTools.put(JSONObject().apply {
                put("name", t.getString("name"))
                put("description", t.optString("description", ""))
                // MCP uses inputSchema, Claude uses input_schema
                put("input_schema", t.optJSONObject("inputSchema") ?: JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })
        }
        cachedTools = claudeTools
        Log.i(TAG, "Loaded ${claudeTools.length()} MCP tools")
        return claudeTools
    }

    /** Call a single MCP tool, return the result as a string for Claude's tool_result. */
    fun callTool(name: String, arguments: JSONObject): String {
        ensureInitialized()
        val result = rpc("tools/call", JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        })
        val content = result.optJSONObject("result")?.optJSONArray("content")
        return if (content != null && content.length() > 0) {
            content.getJSONObject(0).optString("text", result.toString())
        } else {
            result.optJSONObject("error")?.toString() ?: "ok"
        }
    }

    private fun rpc(method: String, params: JSONObject? = null): JSONObject {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id.getAndIncrement())
            put("method", method)
            if (params != null) put("params", params)
        }
        return post(payload)
    }

    private fun rpcNotify(method: String) {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
        }
        try { post(payload) } catch (_: Exception) {}
    }

    private fun post(payload: JSONObject): JSONObject {
        val reqBuilder = Request.Builder()
            .url(mcpUrl)
            .addHeader("Authorization", "Bearer $haToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MT))
        sessionId?.let { reqBuilder.addHeader("Mcp-Session-Id", it) }

        val response = client.newCall(reqBuilder.build()).execute()
        // Capture session ID from response headers if present
        response.header("Mcp-Session-Id")?.let { sessionId = it }

        val body = response.body?.string() ?: "{}"
        response.close()

        // Handle SSE-wrapped response (lines starting with "data: ")
        val json = if (body.contains("data:")) {
            val dataLine = body.lines().firstOrNull { it.startsWith("data:") }
                ?.removePrefix("data:")?.trim() ?: "{}"
            JSONObject(dataLine)
        } else {
            JSONObject(body)
        }

        if (json.has("error")) {
            Log.w(TAG, "MCP error for ${payload.optString("method")}: ${json.optJSONObject("error")}")
        }
        return json
    }
}
