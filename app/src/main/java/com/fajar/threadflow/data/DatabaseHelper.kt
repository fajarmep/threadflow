package com.fajar.threadflow.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

class DatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "threadflow.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_TARGETS = "target_posts"
        private const val COL_T_ID = "id"
        private const val COL_T_POST_ID = "post_id"
        private const val COL_T_URL = "post_url"
        private const val COL_T_AUTHOR = "author_username"
        private const val COL_T_CONTENT = "content_text"
        private const val COL_T_LIKES = "like_count"
        private const val COL_T_REPLIES = "reply_count"
        private const val COL_T_REPOSTS = "repost_count"
        private const val COL_T_SHARES = "share_count"
        private const val COL_T_DATE = "post_date"
        private const val COL_T_SCRAPED = "scraped_at"
        private const val COL_T_STATUS = "status"

        private const val TABLE_REPORTS = "comment_reports"
        private const val COL_R_ID = "id"
        private const val COL_R_POST_ID = "post_id"
        private const val COL_R_URL = "post_url"
        private const val COL_R_AUTHOR = "author_username"
        private const val COL_R_C1_TEXT = "comment1_text"
        private const val COL_R_C2_TEXT = "comment2_text"
        private const val COL_R_C1_ID = "comment1_id"
        private const val COL_R_C2_ID = "comment2_id"
        private const val COL_R_STATUS = "status"
        private const val COL_R_ERROR = "error_message"
        private const val COL_R_TIME = "timestamp"

        @Volatile
        private var INSTANCE: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseHelper(context).also { INSTANCE = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_TARGETS (
                $COL_T_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_T_POST_ID TEXT UNIQUE,
                $COL_T_URL TEXT,
                $COL_T_AUTHOR TEXT,
                $COL_T_CONTENT TEXT,
                $COL_T_LIKES INTEGER,
                $COL_T_REPLIES INTEGER,
                $COL_T_REPOSTS INTEGER,
                $COL_T_SHARES INTEGER,
                $COL_T_DATE TEXT,
                $COL_T_SCRAPED INTEGER,
                $COL_T_STATUS TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_REPORTS (
                $COL_R_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_R_POST_ID TEXT,
                $COL_R_URL TEXT,
                $COL_R_AUTHOR TEXT,
                $COL_R_C1_TEXT TEXT,
                $COL_R_C2_TEXT TEXT,
                $COL_R_C1_ID TEXT,
                $COL_R_C2_ID TEXT,
                $COL_R_STATUS TEXT,
                $COL_R_ERROR TEXT,
                $COL_R_TIME INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TARGETS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_REPORTS")
        onCreate(db)
    }

    fun insertTargetPost(post: TargetPost): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_T_POST_ID, post.postId)
            put(COL_T_URL, post.postUrl)
            put(COL_T_AUTHOR, post.authorUsername)
            put(COL_T_CONTENT, post.contentText)
            put(COL_T_LIKES, post.likeCount)
            put(COL_T_REPLIES, post.replyCount)
            put(COL_T_REPOSTS, post.repostCount)
            put(COL_T_SHARES, post.shareCount)
            put(COL_T_DATE, post.postDate)
            put(COL_T_SCRAPED, post.scrapedAt)
            put(COL_T_STATUS, post.status)
        }
        return db.insertWithOnConflict(TABLE_TARGETS, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getQueuedPosts(): List<TargetPost> {
        val list = mutableListOf<TargetPost>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_TARGETS WHERE $COL_T_STATUS = 'QUEUED' ORDER BY $COL_T_ID ASC",
            null
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(
                    TargetPost(
                        id = c.getLong(c.getColumnIndexOrThrow(COL_T_ID)),
                        postId = c.getString(c.getColumnIndexOrThrow(COL_T_POST_ID)),
                        postUrl = c.getString(c.getColumnIndexOrThrow(COL_T_URL)),
                        authorUsername = c.getString(c.getColumnIndexOrThrow(COL_T_AUTHOR)),
                        contentText = c.getString(c.getColumnIndexOrThrow(COL_T_CONTENT)),
                        likeCount = c.getInt(c.getColumnIndexOrThrow(COL_T_LIKES)),
                        replyCount = c.getInt(c.getColumnIndexOrThrow(COL_T_REPLIES)),
                        repostCount = c.getInt(c.getColumnIndexOrThrow(COL_T_REPOSTS)),
                        shareCount = c.getInt(c.getColumnIndexOrThrow(COL_T_SHARES)),
                        postDate = c.getString(c.getColumnIndexOrThrow(COL_T_DATE)),
                        scrapedAt = c.getLong(c.getColumnIndexOrThrow(COL_T_SCRAPED)),
                        status = c.getString(c.getColumnIndexOrThrow(COL_T_STATUS))
                    )
                )
            }
        }
        return list
    }

    fun deleteTargetPost(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_TARGETS, "$COL_T_ID = ?", arrayOf(id.toString()))
    }

    fun clearQueuedPosts(): Int {
        val db = writableDatabase
        return db.delete(TABLE_TARGETS, "$COL_T_STATUS = 'QUEUED'", null)
    }

    fun updateTargetPostStatus(id: Long, status: String): Int {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_T_STATUS, status)
        }
        return db.update(TABLE_TARGETS, cv, "$COL_T_ID = ?", arrayOf(id.toString()))
    }

    fun isPostAlreadyProcessed(postId: String): Boolean {
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT 1 FROM $TABLE_REPORTS WHERE $COL_R_POST_ID = ? AND $COL_R_STATUS != 'FAILED' LIMIT 1",
            arrayOf(postId)
        )
        c.use {
            return it.moveToFirst()
        }
    }

    fun insertReport(report: CommentReport): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_R_POST_ID, report.postId)
            put(COL_R_URL, report.postUrl)
            put(COL_R_AUTHOR, report.authorUsername)
            put(COL_R_C1_TEXT, report.comment1Text)
            put(COL_R_C2_TEXT, report.comment2Text)
            put(COL_R_C1_ID, report.comment1Id)
            put(COL_R_C2_ID, report.comment2Id)
            put(COL_R_STATUS, report.status)
            put(COL_R_ERROR, report.errorMessage)
            put(COL_R_TIME, report.timestamp)
        }
        return db.insert(TABLE_REPORTS, null, cv)
    }

    fun getAllReports(): List<CommentReport> {
        val list = mutableListOf<CommentReport>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_REPORTS ORDER BY $COL_R_TIME DESC",
            null
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(
                    CommentReport(
                        id = c.getLong(c.getColumnIndexOrThrow(COL_R_ID)),
                        postId = c.getString(c.getColumnIndexOrThrow(COL_R_POST_ID)),
                        postUrl = c.getString(c.getColumnIndexOrThrow(COL_R_URL)),
                        authorUsername = c.getString(c.getColumnIndexOrThrow(COL_R_AUTHOR)),
                        comment1Text = c.getString(c.getColumnIndexOrThrow(COL_R_C1_TEXT)),
                        comment2Text = c.getString(c.getColumnIndexOrThrow(COL_R_C2_TEXT)),
                        comment1Id = c.getString(c.getColumnIndexOrThrow(COL_R_C1_ID)),
                        comment2Id = c.getString(c.getColumnIndexOrThrow(COL_R_C2_ID)),
                        status = c.getString(c.getColumnIndexOrThrow(COL_R_STATUS)),
                        errorMessage = c.getString(c.getColumnIndexOrThrow(COL_R_ERROR)),
                        timestamp = c.getLong(c.getColumnIndexOrThrow(COL_R_TIME))
                    )
                )
            }
        }
        return list
    }

    fun getTodayReportCount(): Int {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_REPORTS WHERE $COL_R_TIME >= ? AND $COL_R_STATUS IN ('SUCCESS', 'PARTIAL')",
            arrayOf(startOfDay.toString())
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return 0
    }

    fun clearAllReports(): Int {
        val db = writableDatabase
        return db.delete(TABLE_REPORTS, null, null)
    }
}
