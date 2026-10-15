package com.fajar.threadflow.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.fajar.threadflow.data.DatabaseHelper
import com.fajar.threadflow.databinding.FragmentReportBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportFragment : Fragment() {

    private var _binding: FragmentReportBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: DatabaseHelper
    private lateinit var reportAdapter: ReportAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = DatabaseHelper.getInstance(requireContext())

        setupUI()
        loadReports()
    }

    private fun setupUI() {
        reportAdapter = ReportAdapter(emptyList()) { report ->
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(report.postUrl))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Tidak dapat membuka link: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.rvReports.layoutManager = LinearLayoutManager(context)
        binding.rvReports.adapter = reportAdapter

        binding.btnExportCsv.setOnClickListener {
            exportCsv()
        }
    }

    fun loadReports() {
        if (_binding == null) return
        val reports = db.getAllReports()
        val successCount = reports.count { it.status == "SUCCESS" }
        val failedCount = reports.count { it.status != "SUCCESS" }

        binding.tvSuccessCount.text = successCount.toString()
        binding.tvFailedCount.text = failedCount.toString()

        reportAdapter.updateReports(reports)
    }

    private fun exportCsv() {
        val reports = db.getAllReports()
        if (reports.isEmpty()) {
            Toast.makeText(context, "Belum ada laporan untuk diekspor", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val sb = java.lang.StringBuilder()
            sb.append("ID,Waktu,Target User,URL,Status,Komen 1,Komen 2,Error\n")
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            for (r in reports) {
                val timeStr = sdf.format(Date(r.timestamp))
                val c1 = r.comment1Text.replace("\"", "\"\"").replace("\n", " ")
                val c2 = r.comment2Text.replace("\"", "\"\"").replace("\n", " ")
                val err = r.errorMessage.replace("\"", "\"\"")
                sb.append("${r.id},\"$timeStr\",\"@${r.authorUsername}\",\"${r.postUrl}\",\"${r.status}\",\"$c1\",\"$c2\",\"$err\"\n")
            }

            val file = File(requireContext().cacheDir, "ThreadFlow_Report_${System.currentTimeMillis()}.csv")
            file.writeText(sb.toString())

            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Bagikan / Ekspor Laporan CSV"))
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal ekspor: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadReports()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
