package com.fajar.threadflow.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trimEnd('/') + "/chat/completions"
                val jsonBody = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "Jawab hanya satu kata: PONG")
                        })
                    })
                    put("max_tokens", 10)
                }

                val requestBuilder = Request.Builder()
                    .url(cleanUrl)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))

                if (apiKey.isNotBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val parsed = JSONObject(bodyString)
                        val choices = parsed.optJSONArray("choices")
                        val content = choices?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content") ?: "Koneksi berhasil."
                        Result.success("Terhubung! Respon: ${content.trim()}")
                    } else {
                        Result.failure(Exception("HTTP ${response.code}: $bodyString"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun generateTwoTierComments(
        baseUrl: String,
        apiKey: String,
        model: String,
        postContent: String,
        author: String,
        productDesc: String,
        affiliateLink: String,
        tone: String
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = baseUrl.trimEnd('/') + "/chat/completions"

            val prompt = """
                Kamu adalah copywriter profesional untuk media sosial Threads.
                Target Postingan dari @$author:
                "$postContent"

                Informasi Produk Afiliasi:
                "$productDesc"

                Link Afiliasi:
                "$affiliateLink"

                Gaya Bahasa / Tone: $tone

                Tugas: Buat 2 tingkat komentar berbahasa Indonesia:
                1. "comment1": Komentar utama bernilai edukatif, membagikan insight/pengetahuan yang relevan dengan topik postingan di atas dan secara halus menyinggung solusi masalah. DILARANG MENYEBUTKAN LINK/URL di komentar 1. Jangan terlihat seperti iklan hard-selling, buat natural agar orang teredukasi dan suka.
                2. "comment2": Komentar balasan (reply) untuk komentar 1. Berisi ajakan menarik (Call To Action), rekomendasi produk spesifik yang sudah dijelaskan, dan wajib sertakan link afiliasi: $affiliateLink.

                Format output WAJIB HANYA JSON murni tanpa markdown triple backtick:
                {"comment1": "teks komentar 1", "comment2": "teks komentar 2"}
            """.trimIndent()

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "Kamu selalu membalas dalam format JSON murni dengan field 'comment1' dan 'comment2'.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.7)
            }

            val requestBuilder = Request.Builder()
                .url(cleanUrl)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))

            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: $bodyString"))
                }

                val root = JSONObject(bodyString)
                val rawContent = root.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                // Clean markdown wrappers if any
                val cleanedJson = rawContent
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()

                val jsonResult = JSONObject(cleanedJson)
                val c1 = jsonResult.optString("comment1", "")
                val c2 = jsonResult.optString("comment2", "")

                if (c1.isNotBlank() && c2.isNotBlank()) {
                    Result.success(Pair(c1, c2))
                } else {
                    Result.failure(Exception("Format JSON AI tidak lengkap: $rawContent"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
