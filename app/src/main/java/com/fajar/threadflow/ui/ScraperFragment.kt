package com.fajar.threadflow.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fajar.threadflow.data.AppPreferences
import com.fajar.threadflow.data.DatabaseHelper
import com.fajar.threadflow.databinding.FragmentScraperBinding
import com.fajar.threadflow.network.ThreadsClient
import kotlinx.coroutines.launch
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

        adapter = TargetPostAdapter(emptyList()) { targetPost ->
            db.deleteTargetPost(targetPost.id)
            loadQueuedPosts()
            Toast.makeText(context, "Dihapus dari antrean", Toast.LENGTH_SHORT).show()
        }

        binding.rvTargets.layoutManager = LinearLayoutManager(context)
        binding.rvTargets.adapter = adapter

        binding.btnClearQueue.setOnClickListener {
            db.clearQueuedPosts()
            loadQueuedPosts()
            Toast.makeText(context, "Antrean berhasil dibersihkan", Toast.LENGTH_SHORT).show()
        }

        binding.btnScrape.setOnClickListener {
            val keyword = binding.etKeyword.text?.toString()?.trim() ?: ""
            if (keyword.isBlank()) {
                binding.layoutKeyword.error = "Masukkan kata kunci / topik"
                return@setOnClickListener
            }
            binding.layoutKeyword.error = null

            val dateStr = binding.etDate.text?.toString()?.trim() ?: ""
            val limitStr = binding.etLimit.text?.toString()?.trim() ?: ""
            val limit = limitStr.toIntOrNull() ?: 0

            performScrape(keyword, dateStr, limit)
        }
    }

    private fun performScrape(keyword: String, dateStr: String, limit: Int) {
        binding.progressScraper.visibility = View.VISIBLE
        binding.btnScrape.isEnabled = false

        lifecycleScope.launch {
            try {
                val results = threadsClient.scrapeViralPosts(
                    keyword = keyword,
                    dateTarget = dateStr,
                    limit = limit,
                    filterIndo = binding.switchIndoOnly.isChecked,
                    filterNegative = binding.switchNegativeFilter.isChecked
                )

                var newCount = 0
                for (item in results) {
                    val rowId = db.insertTargetPost(item)
                    if (rowId > 0) newCount++
                }

                Toast.makeText(context, "Ditemukan ${results.size} post viral ($newCount baru ditambahkan ke antrean).", Toast.LENGTH_LONG).show()
                loadQueuedPosts()
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal scraping: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressScraper.visibility = View.GONE
                binding.btnScrape.isEnabled = true
            }
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
        _binding = null
    }
}
