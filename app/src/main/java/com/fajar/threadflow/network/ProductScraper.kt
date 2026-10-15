package com.fajar.threadflow.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ProductInfo(
    val title: String,
    val description: String
)

object ProductScraper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun fetchProductInfo(url: String): Result<ProductInfo> = withContext(Dispatchers.IO) {
        try {
            var targetUrl = url.trim()
            if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                targetUrl = "https://$targetUrl"
            }

            // Step 1: Probe redirects to resolve destination
            val probeReq = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val probeResp = client.newCall(probeReq).execute()
            val finalUrl = probeResp.request.url.toString()
            probeResp.close()

            // Step 2: Handle Shopee affiliate landing redirects: /opaanlp/{shopId}/{itemId} -> /product/{shopId}/{itemId}
            var fetchUrl = finalUrl
            val shopeeMatch = Regex("/(?:opaanlp|product)/([0-9]+)/([0-9]+)").find(finalUrl)
            if (shopeeMatch != null) {
                val shopId = shopeeMatch.groupValues[1]
                val itemId = shopeeMatch.groupValues[2]
                fetchUrl = "https://shopee.co.id/product/$shopId/$itemId"
            }

            // Step 3: Fetch page with social-crawler User-Agent for server-side rendered OpenGraph metadata
            val fetchReq = Request.Builder()
                .url(fetchUrl)
                .header("User-Agent", "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()

            val resp = client.newCall(fetchReq).execute()
            val rawHtml = resp.body?.string() ?: ""

            if (!resp.isSuccessful && rawHtml.isBlank()) {
                return@withContext Result.failure(Exception("HTTP ${resp.code} saat mengakses tautan"))
            }

            // Step 4: Extract meta tags
            val metaPattern = Pattern.compile("<meta\\s+[^>]*>", Pattern.CASE_INSENSITIVE)
            val matcher = metaPattern.matcher(rawHtml)
            val metaTags = mutableListOf<String>()
            while (matcher.find()) {
                metaTags.add(matcher.group())
            }

            var ogTitle = ""
            var ogDesc = ""

            for (tag in metaTags) {
                // Check for og:title
                if (tag.contains("property=\"og:title\"", ignoreCase = true) ||
                    tag.contains("property='og:title'", ignoreCase = true)) {
                    val m = Regex("content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)
                    if (m != null && ogTitle.isBlank()) {
                        ogTitle = m.groupValues[1]
                    }
                }

                // Check for og:description or name="description"
                if (tag.contains("property=\"og:description\"", ignoreCase = true) ||
                    tag.contains("property='og:description'", ignoreCase = true) ||
                    tag.contains("name=\"description\"", ignoreCase = true) ||
                    tag.contains("name='description'", ignoreCase = true)) {
                    val m = Regex("content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)
                    if (m != null && ogDesc.isBlank()) {
                        ogDesc = m.groupValues[1]
                    }
                }
            }

            // Fallback to <title> if og:title empty
            if (ogTitle.isBlank()) {
                val titleTag = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(rawHtml)
                if (titleTag != null) {
                    ogTitle = titleTag.groupValues[1]
                }
            }

            // Unescape HTML entities
            var cleanTitle = unescapeHtml(ogTitle).trim()
            var cleanDesc = unescapeHtml(ogDesc).trim()

            // Clean store prefixes and suffixes
            cleanTitle = cleanTitle.replace(Regex("^Jual\\s+", RegexOption.IGNORE_CASE), "")
            cleanTitle = cleanTitle.replace(Regex("\\s*\\|\\s*Shopee.*$", RegexOption.IGNORE_CASE), "")
            cleanTitle = cleanTitle.replace(Regex("\\s*\\|\\s*Tokopedia.*$", RegexOption.IGNORE_CASE), "")
            cleanTitle = cleanTitle.replace(Regex("\\s*\\|\\s*TikTok.*$", RegexOption.IGNORE_CASE), "")
            cleanTitle = cleanTitle.replace(Regex("\\s*\\|\\s*Lazada.*$", RegexOption.IGNORE_CASE), "")

            if (cleanTitle.isBlank() && cleanDesc.isBlank()) {
                return@withContext Result.failure(Exception("Tidak dapat menemukan detail produk pada halaman tersebut."))
            }

            Result.success(ProductInfo(cleanTitle, cleanDesc))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun unescapeHtml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
    }
}
