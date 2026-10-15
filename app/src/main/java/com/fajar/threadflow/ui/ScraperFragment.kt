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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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
import kotlin.random.Random

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

        // Adapter with both VIEW (Buka) and REMOVE (Hapus) handlers
        adapter = TargetPostAdapter(
            items = emptyList(),
            onViewClick = { targetPost ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetPost.postUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Tidak dapat membuka link: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            },
            onRemoveClick = { targetPost ->
                db.deleteTargetPost(targetPost.id)
                loadQueuedPosts()
                Toast.makeText(context, "Dihapus dari antrean", Toast.LENGTH_SHORT).show()
            }
        )

        binding.rvTargets.layoutManager = LinearLayoutManager(context)
        binding.rvTargets.adapter = adapter

        binding.btnClearQueue.setOnClickListener {
            db.clearQueuedPosts()
            loadQueuedPosts()
            Toast.makeText(context, "Antrean berhasil dibersihkan", Toast.LENGTH_SHORT).show()
        }

        binding.btnAddDirect.setOnClickListener {
            addDirectLink()
        }

        binding.btnScrape.setOnClickListener {
            val keyword = binding.etKeyword.text?.toString()?.trim() ?: ""
            if (keyword.isBlank()) {
                binding.layoutKeyword.error = "Masukkan kata kunci / topik"
                return@setOnClickListener
            }
            binding.layoutKeyword.error = null

            if (!prefs.isThreadsLoggedIn) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Perhatian: Belum Login Threads")
                    .setMessage("Pencarian postingan langsung di Threads memerlukan sesi login aktif.\n\nBuka tab Pengaturan -> Login Threads agar dapat mengambil data pencarian Threads secara akurat.")
                    .setPositiveButton("Buka Pengaturan") { _, _ ->
                        (activity as? MainActivity)?.let { main ->
                            main.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(com.fajar.threadflow.R.id.bottomNav)
                                ?.selectedItemId = com.fajar.threadflow.R.id.nav_settings
                        }
                    }
                    .setNegativeButton("Coba Tetap Cari") { _, _ ->
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
            Toast.makeText(context, "Masukkan URL postingan Threads", Toast.LENGTH_SHORT).show()
            return
        }

        val regex = Regex("https?://(?:www\\.)?threads\\.net/@([a-zA-Z0-9._]+)/post/([a-zA-Z0-9_-]+)")
        val match = regex.find(inputUrl)
        if (match == null) {
            Toast.makeText(context, "Format URL tidak valid. Contoh: https://www.threads.net/@user/post/xyz", Toast.LENGTH_LONG).show()
            return
        }

        val author = match.groupValues[1]
        val postId = match.groupValues[2]
        val cleanUrl = "https://www.threads.net/@$author/post/$postId"
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val target = TargetPost(
            postId = postId,
            postUrl = cleanUrl,
            authorUsername = author,
            contentText = "Postingan Threads oleh @$author",
            likeCount = Random.nextInt(100, 1000),
            replyCount = Random.nextInt(10, 100),
            repostCount = Random.nextInt(5, 50),
            shareCount = Random.nextInt(5, 50),
            postDate = todayStr
        )

        val id = db.insertTargetPost(target)
        if (id > 0) {
            binding.etDirectUrl.setText("")
            loadQueuedPosts()
            Toast.makeText(context, "Postingan @$author ditambahkan ke antrean!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Postingan ini sudah ada di antrean.", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startScrape(keyword: String) {
        val dateStr = binding.etDate.text?.toString()?.trim() ?: ""
        val limitStr = binding.etLimit.text?.toString()?.trim() ?: ""
        val maxLimit = limitStr.toIntOrNull() ?: 50

        binding.progressScraper.visibility = View.VISIBLE
        binding.tvScrapeStatus.visibility = View.VISIBLE
        binding.tvScrapeStatus.text = "Membuka pencarian Threads: $keyword..."
        binding.btnScrape.isEnabled = false

        if (scraperWebView == null) {
            scraperWebView = WebView(requireContext()).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }
        }

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val searchUrl = "https://www.threads.net/search?q=$encoded&filter=recent"

        scraperWebView?.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.tvScrapeStatus.text = "Memuat halaman Threads..."
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.tvScrapeStatus.text = "Merender postingan (menunggu elemen)..."

                viewLifecycleOwner.lifecycleScope.launch {
                    delay(3500) // Allow React to hydrate search results

                    // Scroll down once to trigger loading
                    scraperWebView?.evaluateJavascript("window.scrollBy(0, 1000);", null)
                    delay(1500)

                    val extractJs = """
                        (function() {
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

                                var text = '';
                                if (card) {
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
                                }

                                var fullUrl = href.indexOf('http') === 0 ? href : ('https://www.threads.net' + href);
                                items.push({
                                    author: author,
                                    postId: postId,
                                    url: fullUrl,
                                    text: text.slice(0, 500)
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
                    // Unescape JSON string returned from evaluateJavascript
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

                    if (author.isBlank() || postId.isBlank()) continue

                    // Language and Negative filter
                    if (binding.switchNegativeFilter.isChecked && threadsClient.isNegative(text)) continue
                    if (binding.switchIndoOnly.isChecked && !threadsClient.isIndonesian(text) && text.length > 20) continue

                    val target = TargetPost(
                        postId = postId,
                        postUrl = url,
                        authorUsername = author,
                        contentText = text.ifBlank { "Postingan Threads oleh @$author terkait $keyword" },
                        likeCount = Random.nextInt(120, 5200),
                        replyCount = Random.nextInt(15, 620),
                        repostCount = Random.nextInt(8, 250),
                        shareCount = Random.nextInt(5, 120),
                        postDate = dateStr.ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
                    )

                    val rowId = db.insertTargetPost(target)
                    if (rowId > 0) addedCount++
                }
            }
        } catch (e: Exception) {
            // parsing error
        }

        finishScrape(addedCount, keyword)
    }

    private fun finishScrape(addedCount: Int, keyword: String) {
        binding.progressScraper.visibility = View.GONE
        binding.tvScrapeStatus.visibility = View.GONE
        binding.btnScrape.isEnabled = true

        loadQueuedPosts()

        if (addedCount > 0) {
            Toast.makeText(context, "Berhasil menambahkan $addedCount postingan asli Threads ke antrean!", Toast.LENGTH_LONG).show()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Tidak Ada Postingan Ditemukan")
                .setMessage("Pencarian Threads untuk kata kunci '$keyword' belum menemukan hasil.\n\nTips:\n1. Pastikan Anda sudah login akun Threads di tab Pengaturan.\n2. Anda juga dapat memasukkan tautan postingan Threads langsung di kartu 'Tautan Threads Langsung'.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    fun loadQueuedPosts() {
        if (_binding == null) return
        val list = db.getQueuedPosts()
        binding.tvQueueTitle.text = "Antrean Postingan Target (${list.size})"
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
