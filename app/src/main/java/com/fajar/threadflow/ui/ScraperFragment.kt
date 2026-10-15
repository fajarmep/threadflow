package com.fajar.threadflow.ui

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fajar.threadflow.R
import com.fajar.threadflow.data.AppPreferences
import com.fajar.threadflow.data.DatabaseHelper
import com.fajar.threadflow.data.TargetPost
import com.fajar.threadflow.databinding.FragmentScraperBinding
import com.fajar.threadflow.network.ThreadsClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ScraperFragment : Fragment() {

    private var _binding: FragmentScraperBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: DatabaseHelper
    private lateinit var prefs: AppPreferences
    private lateinit var threadsClient: ThreadsClient
    private lateinit var adapter: TargetPostAdapter
    private var scraperWebView: WebView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScraperBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        db = DatabaseHelper.getInstance(ctx)
        prefs = AppPreferences(ctx)
        threadsClient = ThreadsClient(ctx)

        setupUI()
        loadQueuedPosts()
    }

    private fun setupUI() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        binding.etDate.setText(todayStr)

        binding.etDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    cal.set(year, month, dayOfMonth)
                    val formatted = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
                    binding.etDate.setText(formatted)
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        binding.switchIndoOnly.isChecked = prefs.filterIndonesianOnly
        binding.switchIndoOnly.setOnCheckedChangeListener { _, isChecked ->
            prefs.filterIndonesianOnly = isChecked
        }

        binding.switchNegativeFilter.isChecked = prefs.filterNegativeKeywords
        binding.switchNegativeFilter.setOnCheckedChangeListener { _, isChecked ->
            prefs.filterNegativeKeywords = isChecked
        }

        adapter = TargetPostAdapter(
            items = emptyList(),
            onViewClick = { targetPost ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetPost.postUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            },
            onRemoveClick = { targetPost ->
                db.deleteTargetPost(targetPost.id)
                loadQueuedPosts()
                Toast.makeText(context, getString(R.string.scraper_toast_removed), Toast.LENGTH_SHORT).show()
            }
        )

        binding.rvTargets.layoutManager = LinearLayoutManager(context)
        binding.rvTargets.adapter = adapter

        binding.btnClearQueue.setOnClickListener {
            db.clearQueuedPosts()
            loadQueuedPosts()
            Toast.makeText(context, getString(R.string.scraper_toast_cleared), Toast.LENGTH_SHORT).show()
        }

        binding.btnAddDirect.setOnClickListener {
            addDirectLink()
        }

        binding.btnScrape.setOnClickListener {
            val keyword = binding.etKeyword.text?.toString()?.trim() ?: ""
            if (keyword.isBlank()) {
                binding.layoutKeyword.error = getString(R.string.scraper_keyword_error)
                return@setOnClickListener
            }
            binding.layoutKeyword.error = null

            if (!prefs.isThreadsLoggedIn) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.scraper_dialog_no_login_title))
                    .setMessage(getString(R.string.scraper_dialog_no_login_msg))
                    .setPositiveButton(getString(R.string.scraper_dialog_btn_open_settings)) { _, _ ->
                        (activity as? MainActivity)?.let { main ->
                            main.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
                                ?.selectedItemId = R.id.nav_settings
                        }
                    }
                    .setNegativeButton(getString(R.string.scraper_dialog_btn_try_anyway)) { _, _ ->
                        startScrape(keyword)
                    }
                    .show()
                return@setOnClickListener
            }

            startScrape(keyword)
        }
    }

    private fun addDirectLink() {
        val inputUrl = binding.etDirectUrl.text?.toString()?.trim() ?: ""
        if (inputUrl.isBlank()) {
            Toast.makeText(context, getString(R.string.scraper_toast_input_url), Toast.LENGTH_SHORT).show()
            return
        }

        // Support both threads.com and threads.net
        val regex = Regex("https?://(?:www\\.)?threads\\.(?:com|net)/@([a-zA-Z0-9._]+)/post/([a-zA-Z0-9_-]+)")
        val match = regex.find(inputUrl)
        if (match == null) {
            Toast.makeText(context, getString(R.string.scraper_toast_invalid_url), Toast.LENGTH_LONG).show()
            return
        }

        val author = match.groupValues[1]
        val postId = match.groupValues[2]
        val cleanUrl = "https://www.threads.com/@$author/post/$postId"
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val target = TargetPost(
            postId = postId,
            postUrl = cleanUrl,
            authorUsername = author,
            contentText = "Postingan Threads oleh @$author",
            likeCount = 0,
            replyCount = 0,
            repostCount = 0,
            shareCount = 0,
            postDate = todayStr
        )

        val id = db.insertTargetPost(target)
        if (id > 0) {
            binding.etDirectUrl.setText("")
            loadQueuedPosts()
            Toast.makeText(context, getString(R.string.scraper_toast_added, author), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, getString(R.string.scraper_toast_exists), Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startScrape(keyword: String) {
        val dateStr = binding.etDate.text?.toString()?.trim() ?: ""
        val limitStr = binding.etLimit.text?.toString()?.trim() ?: ""
        val maxLimit = limitStr.toIntOrNull() ?: 50

        binding.progressScraper.visibility = View.VISIBLE
        binding.tvScrapeStatus.visibility = View.VISIBLE
        binding.tvScrapeStatus.text = getString(R.string.scraper_status_opening)
        binding.btnScrape.isEnabled = false

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        if (scraperWebView == null) {
            scraperWebView = WebView(requireContext()).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                cookieManager.setAcceptThirdPartyCookies(this, true)
            }
        }

        // Sync stored login cookies to both threads.com and threads.net
        if (prefs.threadsCookie.isNotBlank()) {
            val parts = prefs.threadsCookie.split(";")
            for (p in parts) {
                val cookie = p.trim()
                if (cookie.isNotEmpty()) {
                    cookieManager.setCookie("https://www.threads.com", cookie)
                    cookieManager.setCookie(".threads.com", cookie)
                    cookieManager.setCookie("https://www.threads.net", cookie)
                    cookieManager.setCookie(".threads.net", cookie)
                }
            }
            cookieManager.flush()
        }

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        // Use threads.com search endpoint
        val searchUrl = "https://www.threads.com/search/?q=$encoded&filter=recent"

        scraperWebView?.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.tvScrapeStatus.text = getString(R.string.scraper_status_loading)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.tvScrapeStatus.text = getString(R.string.scraper_status_rendering)

                viewLifecycleOwner.lifecycleScope.launch {
                    delay(3500) // Allow React to hydrate search results

                    scraperWebView?.evaluateJavascript("window.scrollBy(0, 1000);", null)
                    delay(1500)

                    val extractJs = """
                        (function() {
                            function parseCount(parent, regex) {
                                if (!parent) return 0;
                                var all = parent.querySelectorAll('[aria-label]');
                                for (var k = 0; k < all.length; k++) {
                                    var lbl = all[k].getAttribute('aria-label') || '';
                                    if (lbl.match(regex)) {
                                        var m = lbl.match(/([0-9.,]+)\s*([kKmM]?)/);
                                        if (m) {
                                            var num = parseFloat(m[1].replace(/,/g, '.'));
                                            var unit = (m[2] || '').toLowerCase();
                                            if (unit === 'k') num *= 1000;
                                            else if (unit === 'm') num *= 1000000;
                                            return Math.round(num);
                                        }
                                    }
                                }
                                return 0;
                            }

                            var items = [];
                            var seen = {};
                            var links = document.querySelectorAll('a[href*="/post/"]');
                            for (var i = 0; i < links.length; i++) {
                                var a = links[i];
                                var href = a.getAttribute('href') || '';
                                var m = href.match(/\/@([a-zA-Z0-9._]+)\/post\/([a-zA-Z0-9_-]+)/);
                                if (!m) continue;
                                var author = m[1];
                                var postId = m[2];
                                if (seen[postId]) continue;
                                seen[postId] = true;

                                var card = a;
                                for (var k = 0; k < 8; k++) {
                                    if (!card || !card.parentElement) break;
                                    card = card.parentElement;
                                    if (card.getAttribute('data-pressable-container') === 'true' || card.tagName === 'ARTICLE') break;
                                }

                                if (!card) continue;

                                // Filter ONLY ROOT POSTS: skip comments/replies
                                var cardText = card.innerText || '';
                                if (cardText.indexOf('Membalas @') !== -1 || cardText.indexOf('Replying to @') !== -1 ||
                                    cardText.indexOf('Membalas ') === 0 || cardText.indexOf('Replying to ') === 0) {
                                    continue;
                                }

                                var text = '';
                                var spans = card.querySelectorAll('span[dir="auto"], div[dir="auto"]');
                                var parts = [];
                                for (var s = 0; s < spans.length; s++) {
                                    var val = (spans[s].innerText || '').trim();
                                    if (val && val !== author && val.indexOf('@') !== 0 && val.length > 3) {
                                        parts.push(val);
                                    }
                                }
                                text = parts.join(' ');
                                if (!text) text = (card.innerText || '').trim();

                                // Extract actual metrics from DOM (zero fake numbers)
                                var likes = parseCount(card, /suka|like/i);
                                var replies = parseCount(card, /balas|repl/i);
                                var reposts = parseCount(card, /kirim ulang|repost/i);

                                var fullUrl = href.indexOf('http') === 0 ? href : ('https://www.threads.com' + href);
                                items.push({
                                    author: author,
                                    postId: postId,
                                    url: fullUrl,
                                    text: text.slice(0, 500),
                                    likes: likes,
                                    replies: replies,
                                    reposts: reposts,
                                    shares: 0
                                });
                            }
                            return JSON.stringify(items);
                        })();
                    """.trimIndent()

                    scraperWebView?.evaluateJavascript(extractJs) { jsonStr ->
                        processScrapedJson(jsonStr, keyword, dateStr, maxLimit)
                    }
                }
            }
        }

        scraperWebView?.loadUrl(searchUrl)
    }

    private fun processScrapedJson(rawJson: String?, keyword: String, dateStr: String, maxLimit: Int) {
        var addedCount = 0
        try {
            if (rawJson != null && rawJson != "null" && rawJson != "\"[]\"" && rawJson != "[]") {
                val clean = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                    val tokener = org.json.JSONTokener(rawJson)
                    tokener.nextValue().toString()
                } else rawJson

                val jsonArray = JSONArray(clean)
                for (i in 0 until jsonArray.length()) {
                    if (addedCount >= maxLimit) break
                    val obj = jsonArray.getJSONObject(i)
                    val author = obj.optString("author", "").trim()
                    val postId = obj.optString("postId", "").trim()
                    val url = obj.optString("url", "").trim()
                    val text = obj.optString("text", "").trim()
                    val likes = obj.optInt("likes", 0)
                    val replies = obj.optInt("replies", 0)
                    val reposts = obj.optInt("reposts", 0)
                    val shares = obj.optInt("shares", 0)

                    if (author.isBlank() || postId.isBlank()) continue

                    if (binding.switchNegativeFilter.isChecked && threadsClient.isNegative(text)) continue
                    if (binding.switchIndoOnly.isChecked && !threadsClient.isIndonesian(text) && text.length > 20) continue

                    val target = TargetPost(
                        postId = postId,
                        postUrl = url,
                        authorUsername = author,
                        contentText = text.ifBlank { "Postingan Threads oleh @$author terkait $keyword" },
                        likeCount = likes,
                        replyCount = replies,
                        repostCount = reposts,
                        shareCount = shares,
                        postDate = dateStr.ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
                    )

                    val rowId = db.insertTargetPost(target)
                    if (rowId > 0) addedCount++
                }
            }
        } catch (_: Exception) {}

        finishScrape(addedCount, keyword)
    }

    private fun finishScrape(addedCount: Int, keyword: String) {
        binding.progressScraper.visibility = View.GONE
        binding.tvScrapeStatus.visibility = View.GONE
        binding.btnScrape.isEnabled = true

        loadQueuedPosts()

        if (addedCount > 0) {
            Toast.makeText(context, getString(R.string.scraper_toast_success, addedCount), Toast.LENGTH_LONG).show()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.scraper_dialog_not_found_title))
                .setMessage(getString(R.string.scraper_dialog_not_found_msg, keyword))
                .setPositiveButton(getString(R.string.dialog_btn_ok), null)
                .show()
        }
    }

    fun loadQueuedPosts() {
        if (_binding == null) return
        val list = db.getQueuedPosts()
        binding.tvQueueTitle.text = getString(R.string.scraper_queue_title, list.size)
        adapter.updateData(list)
    }

    override fun onResume() {
        super.onResume()
        loadQueuedPosts()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scraperWebView?.destroy()
        scraperWebView = null
        _binding = null
    }
}
