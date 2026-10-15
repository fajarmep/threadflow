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

    private fun resolveEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        return if (trimmed.endsWith("/chat/completions")) {
            trimmed
        } else {
            trimmed.trimEnd('/') + "/chat/completions"
        }
    }

    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = resolveEndpoint(baseUrl)
                val jsonBody = JSONObject().apply {
                    put("model", model)
                    put("stream", false)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "Jawab hanya satu kata: PONG")
                        })
                    })
                    put("max_tokens", 15)
                }

                val requestBuilder = Request.Builder()
                    .url(cleanUrl)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))

                if (apiKey.isNotBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP ${response.code}: $bodyString"))
                    }

                    val content = parseCompletionResponse(bodyString)
                    Result.success("Terhubung! Respon: ${content.ifBlank { "OK" }}")
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
            val cleanUrl = resolveEndpoint(baseUrl)

            val prompt = """
                Kamu adalah copywriter profesional kelas dunia untuk media sosial Threads (Meta).
                
                ATURAN SISTEM THREADS:
                Threads memiliki batas ketat maksimal 500 karakter per komentar/reply.
                Jika melebihi 500 karakter, Threads akan menolak postingan.
                
                DATA TARGET:
                Postingan Target dari @$author:
                "$postContent"

                DATA PRODUK DARI PENGGUNA:
                Deskripsi Produk Mentah:
                "$productDesc"

                Link Afiliasi:
                "$affiliateLink"

                Gaya Bahasa / Tone: $tone

                INSTRUKSI KHUSUS:
                1. Deskripsi produk di atas bisa jadi sangat panjang dan detail. JANGAN salin semuanya! Ekstrak poin inti masalah, manfaat terbesar (USP), dan kaitkan secara cerdas dengan topik postingan target di atas.
                2. Buat teks yang singkat, padat, menarik, dan tidak bertele-tele.
                3. "comment1" (KOMENTAR UTAMA):
                   - Panjang WAJIB antara 180 hingga 320 karakter (jangan lebih dari 350 karakter).
                   - Berikan wawasan / edukasi / tips yang relevan. Singgung solusi secara halus tanpa terlihat jualan mentah.
                   - DILARANG KERAS menyertakan link/URL apapun di komentar 1.
                4. "comment2" (KOMENTAR BALASAN):
                   - Panjang teks pengantar WAJIB singkat (80 hingga 200 karakter) ditambah link afiliasi: $affiliateLink.
                   - TOTAL panjang comment2 (termasuk link) WAJIB di bawah 450 karakter.
                   - Buat ajakan bertindak (CTA) yang menggugah rasa penasaran pembaca.

                OUTPUT:
                Wajib balas HANYA format JSON murni tanpa pembuka/penutup markdown:
                {"comment1": "...", "comment2": "..."}
            """.trimIndent()

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("stream", false)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "Kamu selalu membalas dalam format JSON murni dengan key 'comment1' dan 'comment2'. Karakter setiap komentar tidak boleh melebihi 400 karakter.")
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
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))

            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: $bodyString"))
                }

                val rawContent = parseCompletionResponse(bodyString)
                val jsonStr = extractJsonObject(rawContent)

                val jsonResult = JSONObject(jsonStr)
                var c1 = jsonResult.optString("comment1", "").trim()
                var c2 = jsonResult.optString("comment2", "").trim()

                // Programmatic safety guard against Threads 500-char limit
                c1 = enforceLimit(c1, 480)
                c2 = enforceLimit(c2, 480)

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

    private fun enforceLimit(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val cutIndex = text.lastIndexOf(' ', maxChars - 4)
        return if (cutIndex > 0) {
            text.substring(0, cutIndex) + "..."
        } else {
            text.substring(0, maxChars - 3) + "..."
        }
    }

    private fun parseCompletionResponse(bodyString: String): String {
        val trimmed = bodyString.trim()
        if (trimmed.isEmpty()) return ""

        if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            if (root.has("error")) {
                val errObj = root.optJSONObject("error")
                val msg = errObj?.optString("message") ?: root.getString("error")
                throw Exception("AI Error: $msg")
            }
            val choices = root.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val firstChoice = choices.getJSONObject(0)
                val msgObj = firstChoice.optJSONObject("message")
                if (msgObj != null && msgObj.has("content")) {
                    return msgObj.optString("content", "")
                }
                val deltaObj = firstChoice.optJSONObject("delta")
                if (deltaObj != null && deltaObj.has("content")) {
                    return deltaObj.optString("content", "")
                }
                if (firstChoice.has("text")) {
                    return firstChoice.optString("text", "")
                }
            }
            if (root.has("content")) return root.optString("content", "")
            return trimmed
        }

        if (trimmed.contains("data:")) {
            val sb = StringBuilder()
            val lines = trimmed.lines()
            for (line in lines) {
                val l = line.trim()
                if (l.startsWith("data:")) {
                    val dataPayload = l.removePrefix("data:").trim()
                    if (dataPayload == "[DONE]" || dataPayload.isEmpty()) continue
                    try {
                        if (dataPayload.startsWith("{")) {
                            val chunk = JSONObject(dataPayload)
                            val choices = chunk.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val c = choices.getJSONObject(0)
                                val delta = c.optJSONObject("delta")
                                val text = delta?.optString("content", "") ?: ""
                                if (text.isNotEmpty()) sb.append(text)
                                val msg = c.optJSONObject("message")
                                val msgText = msg?.optString("content", "") ?: ""
                                if (msgText.isNotEmpty()) sb.append(msgText)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            val result = sb.toString().trim()
            if (result.isNotEmpty()) return result
        }

        return trimmed
    }

    private fun extractJsonObject(text: String): String {
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1)
        }
        return text
    }
}
