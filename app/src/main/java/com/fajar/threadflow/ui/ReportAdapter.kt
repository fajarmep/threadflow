package com.fajar.threadflow.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fajar.threadflow.data.CommentReport
import com.fajar.threadflow.databinding.ItemReportEntryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportAdapter(
    private var reports: List<CommentReport>,
    private val onViewClick: (CommentReport) -> Unit
) : RecyclerView.Adapter<ReportAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemReportEntryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemReportEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = reports[position]
        holder.binding.apply {
            tvReportAuthor.text = "@${item.authorUsername}"
            val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.timestamp))
            tvReportTime.text = dateStr

            chipReportStatus.text = item.status
            when (item.status) {
                "SUCCESS" -> {
                    chipReportStatus.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#10B981"))
                }
                "PARTIAL" -> {
                    chipReportStatus.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                }
                else -> {
                    chipReportStatus.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                }
            }

            tvComment1Snippet.text = "Komen 1: ${item.comment1Text}"
            tvComment2Snippet.text = if (item.comment2Text.isNotBlank()) "Komen 2: ${item.comment2Text}" else "Komen 2: (Tidak terkirim)"

            btnViewPost.setOnClickListener {
                onViewClick(item)
            }
        }
    }

    override fun getItemCount(): Int = reports.size

    fun updateReports(newReports: List<CommentReport>) {
        reports = newReports
        notifyDataSetChanged()
    }
}
