package com.newoether.agora.newgpt

import android.content.Context
import com.newoether.agora.util.SecretCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

class GitHubConnector(context: Context) {
    private val prefs = context.getSharedPreferences("newgpt_github", Context.MODE_PRIVATE)
    private val client = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    var token: String
        get() = SecretCrypto.decrypt(prefs.getString("token", "").orEmpty())
        private set(value) { prefs.edit().putString("token", SecretCrypto.encrypt(value)).apply() }

    fun setToken(value: String) { token = value.trim() }
    fun clearToken() { prefs.edit().remove("token").apply() }

    suspend fun listRepositories(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            requestJson("https://api.github.com/user/repos?per_page=100&sort=updated") { body ->
                val array = JSONArray(body)
                buildList {
                    for (i in 0 until array.length()) add(array.getJSONObject(i).getString("full_name"))
                }
            }
        }
    }

    suspend fun createOrUpdateFile(
        repository: String,
        path: String,
        content: String,
        message: String,
        branch: String = "main",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(repository.matches(Regex("[^/\\s]+/[^/\\s]+"))) {
                "Nieprawidłowe repozytorium owner/name."
            }
            require(path.isNotBlank()) { "Ścieżka pliku jest pusta." }

            val url = "https://api.github.com/repos/" + repository +
                "/contents/" + path.trimStart('/') + "?ref=" + branch
            val existing = runCatching {
                requestJson(url) { body ->
                    JSONObject(body).optString("sha").takeIf { it.isNotBlank() }
                }
            }.getOrNull()

            val encoded = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
            val payload = JSONObject()
                .put("message", message)
                .put("content", encoded)
                .put("branch", branch)
            existing?.let { payload.put("sha", it) }

            requestJson(
                "https://api.github.com/repos/" + repository +
                    "/contents/" + path.trimStart('/'),
                method = "PUT",
                body = payload.toString(),
            ) { JSONObject(it).optString("content").ifBlank { "OK" } }
        }
    }

    private fun <T> requestJson(
        url: String,
        method: String = "GET",
        body: String? = null,
        transform: (String) -> T,
    ): T {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "NewGPT-Android")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer " + token)
        if (method != "GET") builder.method(method, body.orEmpty().toRequestBody(jsonType))
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrDefault(text)
                error("GitHub " + response.code + ": " + message)
            }
            return transform(text)
        }
    }
}
