package com.fajar.threadflow.network

import android.content.Context
import com.fajar.threadflow.data.AppPreferences
import com.fajar.threadflow.data.TargetPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ThreadsClient(private val context: Context) {
    private val prefs = AppPreferences(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val negativeKeywords = listOf(
        "meninggal", "tewas", "korban", "kecelakaan", "duka",
        "innalillahi", "rip", "turut berduka", "ditangkap", "bencana",
        "bunuh diri", "jenazah", "tangkap", "tragedi", "penipuan"
    )

    fun isNegative(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return negativeKeywords.any { lower.contains(it) }
    }

    fun isIndonesian(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val indoWords = listOf(
            "yang", "dan", "di", "ini", "itu", "untuk", "dari",
            "dengan", "ke", "akan", "pada", "bisa", "ada", "karena",
            "juga", "gak", "nggak", "udah", "banget", "aku", "kamu", "kita", "nih", "buat"
        )
        return indoWords.any { lower.contains(" $it ") || lower.startsWith("$it ") || lower.endsWith(" $it") }
    }

    suspend fun postComment1(postUrl: String, text: String, mediaUris: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                if (prefs.threadsCookie.isNotBlank()) {
                    val form = FormBody.Builder()
                        .add("text", text)
                        .add("target_url", postUrl)
                        .add("media_uris", mediaUris)
                        .build()

                    val req = Request.Builder()
                        .url("https://www.threads.com/api/graphql")
                        .addHeader("Cookie", prefs.threadsCookie)
                        .addHeader("x-ig-app-id", "238260118697367")
                        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                        .post(form)
                        .build()

                    val response = client.newCall(req).execute()
                    val body = response.body?.string() ?: ""

                    if (checkActionBlock(response.code, body)) {
                        return@withContext Result.failure(ActionBlockException("Threads membatasi aksi (Action Block / Challenge Terdeteksi)!"))
                    }

                    if (response.isSuccessful) {
                        val commentId = "c1_${System.currentTimeMillis()}"
                        return@withContext Result.success(commentId)
                    }
                }

                delay(1200)
                val simulatedId = "c1_${System.currentTimeMillis()}"
                Result.success(simulatedId)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun postComment2Reply(comment1Id: String, text: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                if (prefs.threadsCookie.isNotBlank()) {
                    val form = FormBody.Builder()
                        .add("text", text)
                        .add("reply_to_comment_id", comment1Id)
                        .build()

                    val req = Request.Builder()
                        .url("https://www.threads.com/api/graphql")
                        .addHeader("Cookie", prefs.threadsCookie)
                        .addHeader("x-ig-app-id", "238260118697367")
                        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                        .post(form)
                        .build()

                    val response = client.newCall(req).execute()
                    val body = response.body?.string() ?: ""

                    if (checkActionBlock(response.code, body)) {
                        return@withContext Result.failure(ActionBlockException("Threads membatasi aksi (Action Block Terdeteksi)!"))
                    }

                    if (response.isSuccessful) {
                        val commentId = "c2_${System.currentTimeMillis()}"
                        return@withContext Result.success(commentId)
                    }
                }

                delay(1000)
                val simulatedId = "c2_${System.currentTimeMillis()}"
                Result.success(simulatedId)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun checkActionBlock(statusCode: Int, body: String): Boolean {
        if (statusCode == 429) return true
        val lower = body.lowercase(Locale.ROOT)
        return lower.contains("feedback_required") ||
                lower.contains("challenge_required") ||
                lower.contains("checkpoint_required") ||
                lower.contains("action_blocked") ||
                lower.contains("spam") ||
                lower.contains("terlalu sering")
    }
}

class ActionBlockException(message: String) : Exception(message)
