package com.fajar.threadflow.data

data class TargetPost(
    val id: Long = 0,
    val postId: String,
    val postUrl: String,
    val authorUsername: String,
    val contentText: String,
    val likeCount: Int,
    val replyCount: Int,
    val repostCount: Int,
    val shareCount: Int,
    val postDate: String,
    val scrapedAt: Long = System.currentTimeMillis(),
    val status: String = "QUEUED" // QUEUED, PROCESSED, SKIPPED
)
