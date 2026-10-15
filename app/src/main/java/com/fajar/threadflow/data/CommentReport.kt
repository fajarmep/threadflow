package com.fajar.threadflow.data

data class CommentReport(
    val id: Long = 0,
    val postId: String,
    val postUrl: String,
    val authorUsername: String,
    val comment1Text: String,
    val comment2Text: String,
    val comment1Id: String = "",
    val comment2Id: String = "",
    val status: String, // SUCCESS, PARTIAL, FAILED
    val errorMessage: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
