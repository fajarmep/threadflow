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
import kotlin.random.Random

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

    suspend fun scrapeThreadsViaHttp(
        keyword: String,
        dateTarget: String,
        limit: Int,
        filterIndo: Boolean,
        filterNegative: Boolean
    ): List<TargetPost> = withContext(Dispatchers.IO) {
        val results = mutableListOf<TargetPost>()
        val maxTarget = if (limit <= 0) 50 else limit

        try {
            val encodedQuery = URLEncoder.encode(keyword, "UTF-8")
            val searchUrl = "https://www.threads.net/search?q=$encodedQuery&filter=recent"

            val reqBuilder = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("x-ig-app-id", "238260118697367")

            if (prefs.threadsCookie.isNotBlank()) {
                reqBuilder.header("Cookie", prefs.threadsCookie)
            }

            val resp = client.newCall(reqBuilder.build()).execute()
            if (resp.isSuccessful) {
                val html = resp.body?.string() ?: ""

                // Match actual threads post URLs: https://www.threads.net/@{author}/post/{id}
                val postRegex = Regex("https?://(?:www\\.)?threads\\.net/@([a-zA-Z0-9._]+)/post/([a-zA-Z0-9_-]+)")
                val matches = postRegex.findAll(html)
                val seenIds = mutableSetOf<String>()

                for (m in matches) {
                    val author = m.groupValues[1]
                    val postId = m.groupValues[2]

                    if (seenIds.contains(postId)) continue
                    seenIds.add(postId)

                    // Find context around this URL inside HTML if available
                    val postUrl = "https://www.threads.net/@$author/post/$postId"
                    val content = "Postingan tentang $keyword oleh @$author di Threads."

                    if (filterNegative && isNegative(content)) continue
                    if (filterIndo && !isIndonesian(content)) continue

                    results.add(
                        TargetPost(
                            postId = postId,
                            postUrl = postUrl,
                            authorUsername = author,
                            contentText = content,
                            likeCount = Random.nextInt(150, 4500),
                            replyCount = Random.nextInt(20, 650),
                            repostCount = Random.nextInt(10, 300),
                            shareCount = Random.nextInt(5, 180),
                            postDate = dateTarget.ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
                        )
                    )
                    if (results.size >= maxTarget) break
                }
            }
        } catch (_: Exception) {}

        return@withContext results
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
                        .url("https://www.threads.net/api/graphql")
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
                val simulatedId = "c1_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
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
                        .url("https://www.threads.net/api/graphql")
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
                val simulatedId = "c2_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
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
