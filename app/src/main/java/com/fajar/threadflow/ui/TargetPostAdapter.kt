package com.fajar.threadflow.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fajar.threadflow.data.TargetPost
import com.fajar.threadflow.databinding.ItemTargetPostBinding

class TargetPostAdapter(
    private var items: List<TargetPost>,
    private val onRemoveClick: (TargetPost) -> Unit
) : RecyclerView.Adapter<TargetPostAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemTargetPostBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTargetPostBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.apply {
            tvAuthor.text = "@${item.authorUsername}"
            tvDate.text = item.postDate
            tvContent.text = item.contentText
            tvLikes.text = "❤️ ${formatCount(item.likeCount)}"
            tvComments.text = "💬 ${formatCount(item.replyCount)}"
            tvReposts.text = "🔁 ${formatCount(item.repostCount)}"

            btnRemove.setOnClickListener {
                onRemoveClick(item)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<TargetPost>) {
        items = newItems
        notifyDataSetChanged()
    }

    private fun formatCount(count: Int): String {
        return when {
            count >= 1000000 -> String.format("%.1fM", count / 1000000.0)
            count >= 1000 -> String.format("%.1fk", count / 1000.0)
            else -> count.toString()
        }
    }
}
