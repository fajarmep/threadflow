package com.fajar.threadflow.ui

import android.annotation.SuppressLint
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

class ScraperFragment : Fragment() {

    private var _binding: FragmentScraperBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: DatabaseHelper
    private lateinit var prefs: AppPreferences
    private lateinit var threadsClient: ThreadsClient
    private lateinit var adapter: TargetPostAdapter

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

        val regex = Regex("https?://(?:www\\.)?threads\\.(?:com|net)/@([a-zA-Z0-9._]+)/post/([a-zA-Z0-9_-]+)")
        val match = regex.find(inputUrl)
        if (match == null) {
            Toast.makeText(context, getString(R.string.scraper_toast_invalid_url), Toast.LENGTH_LONG).show()
            return
        }

        val author = match.groupValues[1]
        val postId = match.groupValues[2]
        val cleanUrl = "https://www.threads.com/@$author/post/$postId"

        if (db.isPostAlreadyProcessed(postId)) {
            Toast.makeText(context, getString(R.string.scraper_toast_already_commented), Toast.LENGTH_LONG).show()
            return
        }
        if (db.isPostAlreadyQueued(postId)) {
            Toast.makeText(context, getString(R.string.scraper_toast_exists), Toast.LENGTH_SHORT).show()
            return
        }

        val target = TargetPost(
            postId = postId,
            postUrl = cleanUrl,
            authorUsername = author,
            contentText = "Postingan Threads oleh @$author",
            likeCount = 0,
            replyCount = 0,
            repostCount = 0,
            shareCount = 0,
            postDate = getString(R.string.scraper_age_just_added)
        )

        val id = db.insertTargetPost(target)
        if (id > 0) {
            binding.etDirectUrl.setText("")
            loadQueuedPosts()
            Toast.makeText(context, getString(R.string.scraper_toast_added, author), Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startScrape(keyword: String) {
        val limitStr = binding.etLimit.text?.toString()?.trim() ?: ""
        val maxLimit = limitStr.toIntOrNull() ?: 50

        binding.progressScraper.visibility = View.VISIBLE
        binding.tvScrapeStatus.visibility = View.VISIBLE
        binding.tvScrapeStatus.text = getString(R.string.scraper_status_opening)
        binding.btnScrape.isEnabled = false

        // Show live preview card — the WebView MUST be in layout with physical dimensions
        // so Chromium actually renders the page and populates the DOM tree
        binding.cardScraperPreview.visibility = View.VISIBLE
        binding.layoutOverlay.visibility = View.VISIBLE
        binding.tvOverlayStatus.text = getString(R.string.scraper_status_opening)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        // Use the layout-embedded WebView (has physical dimensions → Chromium renders properly)
        val liveWebView = binding.liveWebView
        liveWebView.settings.javaScriptEnabled = true
        liveWebView.settings.domStorageEnabled = true
        liveWebView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        cookieManager.setAcceptThirdPartyCookies(liveWebView, true)

        // Sync stored login cookies
        if (prefs.threadsCookie.isNotBlank()) {
            val parts = prefs.threadsCookie.split(";")
            for (p in parts) {
                val cookie = p.trim()
                if (cookie.isNotEmpty()) {
                    cookieManager.setCookie("https://www.threads.com", cookie)
                    cookieManager.setCookie(".threads.com", cookie)
                }
            }
            cookieManager.flush()
        }

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val searchUrl = "https://www.threads.com/search?q=$encoded&filter=recent"

        liveWebView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (_binding == null) return
                binding.tvScrapeStatus.text = getString(R.string.scraper_status_loading)
                binding.tvOverlayStatus.text = getString(R.string.scraper_status_loading)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (_binding == null) return
                binding.tvScrapeStatus.text = getString(R.string.scraper_status_rendering)
                binding.tvOverlayStatus.text = getString(R.string.scraper_status_rendering)

                viewLifecycleOwner.lifecycleScope.launch {
                    // Progressive scroll to trigger lazy-loading of React virtualized list
                    for (i in 1..6) {
                        if (_binding == null) return@launch
                        liveWebView.evaluateJavascript("window.scrollBy(0, 800);", null)
                        delay(1200)
                    }

                    if (_binding == null) return@launch
                    binding.tvOverlayStatus.text = getString(R.string.scraper_status_extracting)
                    delay(500)

                    val extractJs = buildExtractionJs()
                    liveWebView.evaluateJavascript(extractJs) { jsonStr ->
                        processScrapedJson(jsonStr, keyword, maxLimit)
                    }
                }
            }
        }

        liveWebView.loadUrl(searchUrl)
    }

    /**
     * Build JavaScript that extracts posts from Threads search DOM.
     *
     * Strategy: Find all <a href="/@user/post/id">. Climb parent DOM to find
     * the post card container (identified by containing SVG action icons or
     * data-virtualized attribute). Extract metrics from sibling spans next
     * to SVG Like/Comment/Repost icons. Extract age from <time datetime>.
     */
    private fun buildExtractionJs(): String = """
        (function() {
            function getMetric(container, keywords) {
                var svgs = container.querySelectorAll('svg');
                for (var s = 0; s < svgs.length; s++) {
                    var svg = svgs[s];
                    var titleEl = svg.querySelector('title');
                    var titleText = titleEl ? titleEl.textContent.toLowerCase() : '';
                    var ariaLabel = (svg.getAttribute('aria-label') || '').toLowerCase();
                    var svgTitle = (svg.getAttribute('title') || '').toLowerCase();
                    var combined = titleText + ' ' + ariaLabel + ' ' + svgTitle;

                    var matched = false;
                    for (var k = 0; k < keywords.length; k++) {
                        if (combined.indexOf(keywords[k]) !== -1) { matched = true; break; }
                    }
                    if (!matched) continue;

                    var candidates = [];
                    if (svg.nextElementSibling) candidates.push(svg.nextElementSibling);
                    var p = svg.parentElement;
                    if (p && p.nextElementSibling) candidates.push(p.nextElementSibling);
                    if (p && p.parentElement && p.parentElement.nextElementSibling)
                        candidates.push(p.parentElement.nextElementSibling);

                    for (var c = 0; c < candidates.length; c++) {
                        var el = candidates[c];
                        var txt = (el.textContent || '').trim();
                        if (!txt) continue;
                        var spans = el.querySelectorAll('span');
                        if (spans.length > 0) {
                            for (var si = 0; si < spans.length; si++) {
                                var st = (spans[si].textContent || '').trim();
                                if (st && /^[0-9]/.test(st)) { txt = st; break; }
                            }
                        }
                        var nm = txt.match(/^([0-9][0-9.,]*)\s*([kKmM]?)$/);
                        if (nm) {
                            var n = parseFloat(nm[1].replace(/,/g, ''));
                            var u = (nm[2] || '').toLowerCase();
                            if (u === 'k') n *= 1000;
                            else if (u === 'm') n *= 1000000;
                            return Math.round(n);
                        }
                    }
                }
                return 0;
            }

            function findPostCard(link) {
                var node = link;
                for (var d = 0; d < 12; d++) {
                    node = node.parentElement;
                    if (!node) return null;
                    if (node.querySelector('svg title')) return node;
                    if (node.hasAttribute && node.hasAttribute('data-virtualized')) return node;
                }
                return null;
            }

            var items = [];
            var seen = {};
            var links = document.querySelectorAll('a[href*="/post/"]');

            for (var i = 0; i < links.length; i++) {
                var a = links[i];
                var href = a.getAttribute('href') || '';
                var m = href.match(/\/@([a-zA-Z0-9._]+)\/post\/([a-zA-Z0-9_-]+)/);
                if (!m) continue;
                if (href.indexOf('/media') !== -1) continue;
                var author = m[1];
                var postId = m[2];
                if (seen[postId]) continue;
                seen[postId] = true;

                var card = findPostCard(a);
                if (!card) continue;

                var cardText = card.textContent || '';
                if (cardText.indexOf('Membalas @') !== -1 ||
                    cardText.indexOf('Replying to @') !== -1) {
                    continue;
                }

                var spans = card.querySelectorAll('span[dir="auto"], div[dir="auto"]');
                var parts = [];
                for (var s = 0; s < spans.length; s++) {
                    var v = (spans[s].textContent || '').trim();
                    if (v && v !== author && v.indexOf('@') !== 0 && v.length > 5) {
                        if (/^\d{1,2}\/\d{1,2}\/\d{2,4}$/.test(v)) continue;
                        parts.push(v);
                    }
                }
                var text = parts.length > 1 ? parts[1] : (parts.length > 0 ? parts[0] : '');
                if (!text) text = cardText.substring(0, 300);

                var timeEl = card.querySelector('time');
                var timeText = timeEl ? (timeEl.textContent || '') : '';
                var datetimeStr = timeEl ? (timeEl.getAttribute('datetime') || '') : '';
                var timestamp = datetimeStr ? new Date(datetimeStr).getTime() : 0;

                var ageStr = timeText;
                if (timestamp > 0 && !isNaN(timestamp)) {
                    var diffSec = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));
                    var mn = Math.floor(diffSec / 60);
                    var hr = Math.floor(diffSec / 3600);
                    var day = Math.floor(diffSec / 86400);
                    if (mn < 60) ageStr = mn + 'm';
                    else if (hr < 24) ageStr = hr + 'h';
                    else if (day < 7) ageStr = day + 'd';
                    else if (day < 30) ageStr = Math.floor(day / 7) + 'w';
                    else ageStr = Math.floor(day / 30) + 'mo';
                }
                if (!ageStr) ageStr = '?';

                var likes = getMetric(card, ['like', 'suka']);
                var replies = getMetric(card, ['comment', 'reply', 'balas', 'komentar']);
                var reposts = getMetric(card, ['repost', 'quote']);

                var fullUrl = href.indexOf('http') === 0 ? href : ('https://www.threads.com' + href);

                items.push({
                    author: author,
                    postId: postId,
                    url: fullUrl,
                    text: text.substring(0, 500),
                    likes: likes,
                    replies: replies,
                    reposts: reposts,
                    shares: 0,
                    ageStr: ageStr,
                    timestamp: timestamp
                });
            }

            items.sort(function(a, b) {
                var scoreA = (a.timestamp / 1000) + (a.likes * 60) + (a.replies * 180);
                var scoreB = (b.timestamp / 1000) + (b.likes * 60) + (b.replies * 180);
                return scoreB - scoreA;
            });

            return JSON.stringify(items);
        })();
    """.trimIndent()

    private fun processScrapedJson(rawJson: String?, keyword: String, maxLimit: Int) {
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
                    val ageStr = obj.optString("ageStr", "?")

                    if (author.isBlank() || postId.isBlank()) continue

                    // Strict deduplication
                    if (db.isPostAlreadyProcessed(postId) || db.isPostAlreadyQueued(postId)) {
                        continue
                    }

                    if (binding.switchNegativeFilter.isChecked && threadsClient.isNegative(text)) continue
                    if (binding.switchIndoOnly.isChecked && !threadsClient.isIndonesian(text) && text.length > 30) continue

                    val target = TargetPost(
                        postId = postId,
                        postUrl = url,
                        authorUsername = author,
                        contentText = text.ifBlank { "Postingan @$author: $keyword" },
                        likeCount = likes,
                        replyCount = replies,
                        repostCount = reposts,
                        shareCount = shares,
                        postDate = ageStr
                    )

                    val rowId = db.insertTargetPost(target)
                    if (rowId > 0) addedCount++
                }
            }
        } catch (_: Exception) {}

        finishScrape(addedCount, keyword)
    }

    private fun finishScrape(addedCount: Int, keyword: String) {
        if (_binding == null) return

        binding.progressScraper.visibility = View.GONE
        binding.tvScrapeStatus.visibility = View.GONE
        binding.btnScrape.isEnabled = true
        binding.cardScraperPreview.visibility = View.GONE

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
        try {
            binding.liveWebView.stopLoading()
            binding.liveWebView.loadUrl("about:blank")
        } catch (_: Exception) {}
        _binding = null
    }
}
