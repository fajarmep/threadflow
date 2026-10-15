package com.fajar.threadflow.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.fajar.threadflow.R
import com.fajar.threadflow.data.AppPreferences
import com.fajar.threadflow.data.CommentReport
import com.fajar.threadflow.data.DatabaseHelper
import com.fajar.threadflow.network.ActionBlockException
import com.fajar.threadflow.network.AiClient
import com.fajar.threadflow.network.ThreadsClient
import com.fajar.threadflow.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

class CommentRunnerService : Service() {

    companion object {
        const val CHANNEL_ID = "threadflow_channel_id"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.fajar.threadflow.START"
        const val ACTION_PAUSE = "com.fajar.threadflow.PAUSE"
        const val ACTION_RESUME = "com.fajar.threadflow.RESUME"
        const val ACTION_STOP = "com.fajar.threadflow.STOP"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var db: DatabaseHelper
    private lateinit var prefs: AppPreferences
    private lateinit var aiClient: AiClient
    private lateinit var threadsClient: ThreadsClient

    @Volatile
    private var isPaused = false

    @Volatile
    private var isStopped = false

    override fun onCreate() {
        super.onCreate()
        db = DatabaseHelper.getInstance(this)
        prefs = AppPreferences(this)
        aiClient = AiClient()
        threadsClient = ThreadsClient(this)

        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isPaused = false
                isStopped = false
                startForeground(NOTIFICATION_ID, buildNotification("Memulai antrean komentar...", 0, 0))
                RunnerEvents.updateState(RunnerEvents.STATE_RUNNING)
                RunnerEvents.addLog("Runner dimulai.")
                startRunnerLoop()
            }
            ACTION_PAUSE -> {
                isPaused = true
                RunnerEvents.updateState(RunnerEvents.STATE_PAUSED)
                RunnerEvents.addLog("Runner dijeda (Paused).")
                updateNotificationText("Runner dijeda.")
            }
            ACTION_RESUME -> {
                isPaused = false
                RunnerEvents.updateState(RunnerEvents.STATE_RUNNING)
                RunnerEvents.addLog("Runner dilanjutkan (Resumed).")
                updateNotificationText("Runner berjalan kembali.")
            }
            ACTION_STOP -> {
                stopRunner("Runner dihentikan oleh pengguna.")
            }
        }
        return START_NOT_STICKY
    }

    private fun startRunnerLoop() {
        serviceScope.launch {
            val queuedPosts = db.getQueuedPosts()
            val total = queuedPosts.size
            RunnerEvents.updateProgress(0, total)

            if (queuedPosts.isEmpty()) {
                RunnerEvents.addLog("Antrean kosong. Silakan scrape postingan terlebih dahulu.")
                stopRunner("Antrean postingan kosong.")
                return@launch
            }

            var processedCount = 0

            for (post in queuedPosts) {
                if (isStopped) break

                // Check paused loop
                while (isPaused && !isStopped) {
                    delay(1000)
                }
                if (isStopped) break

                // Guard: Check daily safety cap
                val todayCount = db.getTodayReportCount()
                if (todayCount >= prefs.dailySafetyCap) {
                    RunnerEvents.addLog("Batas harian tercapai ($todayCount / ${prefs.dailySafetyCap} post). Runner berhenti aman.")
                    stopRunner("Daily safety cap tercapai ($todayCount post).")
                    break
                }

                // Check duplicate
                if (db.isPostAlreadyProcessed(post.postId)) {
                    RunnerEvents.addLog("Post @${post.authorUsername} sudah pernah dikomentari. Melewati.")
                    db.updateTargetPostStatus(post.id, "SKIPPED")
                    processedCount++
                    RunnerEvents.updateProgress(processedCount, total)
                    continue
                }

                updateNotificationText("Memproses @${post.authorUsername} ($processedCount/$total)")
                RunnerEvents.addLog("Target: @${post.authorUsername} - Menghasilkan copy AI...")

                // Step 1: AI Copy generation
                var comment1Text = ""
                var comment2Text = ""

                val aiResult = aiClient.generateTwoTierComments(
                    baseUrl = prefs.aiBaseUrl,
                    apiKey = prefs.aiApiKey,
                    model = prefs.aiModel,
                    postContent = post.contentText,
                    author = post.authorUsername,
                    productDesc = prefs.campaignDescription.ifBlank { "Solusi produk berkualitas untuk kebutuhan sehari-hari." },
                    affiliateLink = prefs.campaignAffiliateLink.ifBlank { "https://threads.net" },
                    tone = prefs.campaignTone
                )

                if (aiResult.isSuccess) {
                    val pair = aiResult.getOrThrow()
                    comment1Text = pair.first
                    comment2Text = pair.second
                    RunnerEvents.addLog("Copy AI berhasil dibuat.")
                } else {
                    RunnerEvents.addLog("Peringatan AI: ${aiResult.exceptionOrNull()?.message}. Menggunakan fallback cerdas.")
                    comment1Text = "Menarik sekali pembahasannya kak @${post.authorUsername}. Hal seperti ini memang butuh solusi praktis agar tidak menyita waktu kita sehari-hari."
                    comment2Text = "Untuk informasi produk dan solusi lengkapnya bisa langsung cek tautan resminya di sini: ${prefs.campaignAffiliateLink}"
                }

                // Step 2: Post Comment 1
                RunnerEvents.addLog("Mengirim Komentar 1 (Edukasi & Media)...")
                val c1Result = threadsClient.postComment1(post.postUrl, comment1Text, prefs.campaignMediaUris)

                if (c1Result.isFailure) {
                    val err = c1Result.exceptionOrNull()
                    if (err is ActionBlockException) {
                        RunnerEvents.addLog("PERINGATAN KERAS: ${err.message}")
                        stopRunner("ACTION BLOCK TERDETEKSI! Auto-stop demi keamanan akun.")
                        break
                    }

                    RunnerEvents.addLog("Gagal kirim komentar 1: ${err?.message}")
                    db.insertReport(
                        CommentReport(
                            postId = post.postId,
                            postUrl = post.postUrl,
                            authorUsername = post.authorUsername,
                            comment1Text = comment1Text,
                            comment2Text = "",
                            status = "FAILED",
                            errorMessage = err?.message ?: "Gagal post komentar 1"
                        )
                    )
                    db.updateTargetPostStatus(post.id, "PROCESSED")
                    processedCount++
                    RunnerEvents.updateProgress(processedCount, total)
                    continue
                }

                val comment1Id = c1Result.getOrThrow()
                RunnerEvents.addLog("Komentar 1 berhasil terkirim! (ID: $comment1Id)")

                // Micro delay 3-5 seconds (natural buffer)
                val microDelay = Random.nextInt(3, 6)
                RunnerEvents.addLog("Jeda mikro ${microDelay}s sebelum komentar balasan...")
                delay(microDelay * 1000L)

                if (isStopped) break

                // Step 3: Post Comment 2 (Reply to Comment 1)
                RunnerEvents.addLog("Mengirim Komentar 2 (CTA & Link Afiliasi)...")
                val c2Result = threadsClient.postComment2Reply(comment1Id, comment2Text)
                var reportStatus = "SUCCESS"
                var comment2Id = ""
                var errMsg = ""

                if (c2Result.isSuccess) {
                    comment2Id = c2Result.getOrThrow()
                    RunnerEvents.addLog("Komentar 2 berhasil terkirim! Rantai komentar lengkap.")
                } else {
                    reportStatus = "PARTIAL"
                    errMsg = c2Result.exceptionOrNull()?.message ?: "Gagal reply komentar 2"
                    RunnerEvents.addLog("Peringatan Komentar 2: $errMsg")
                }

                // Record report in SQLite
                db.insertReport(
                    CommentReport(
                        postId = post.postId,
                        postUrl = post.postUrl,
                        authorUsername = post.authorUsername,
                        comment1Text = comment1Text,
                        comment2Text = comment2Text,
                        comment1Id = comment1Id,
                        comment2Id = comment2Id,
                        status = reportStatus,
                        errorMessage = errMsg
                    )
                )
                db.updateTargetPostStatus(post.id, "PROCESSED")

                processedCount++
                RunnerEvents.updateProgress(processedCount, total)

                if (processedCount < total && !isStopped) {
                    val minD = prefs.delayMinSeconds.coerceAtLeast(10)
                    val maxD = prefs.delayMaxSeconds.coerceAtLeast(minD)
                    val randomDelaySec = Random.nextInt(minD, maxD + 1)
                    RunnerEvents.addLog("Jeda acak anti-ban: ${randomDelaySec} detik sebelum post berikutnya...")

                    var elapsed = 0
                    while (elapsed < randomDelaySec && !isStopped) {
                        while (isPaused && !isStopped) {
                            delay(1000)
                        }
                        delay(1000)
                        elapsed++
                        if (elapsed % 15 == 0) {
                            val remaining = randomDelaySec - elapsed
                            updateNotificationText("Menunggu jeda: ${remaining}s ($processedCount/$total)")
                        }
                    }
                }
            }

            if (!isStopped) {
                RunnerEvents.addLog("Semua antrean berhasil diselesaikan ($processedCount post).")
                stopRunner("Selesai memproses semua antrean.")
            }
        }
    }

    private fun stopRunner(reason: String) {
        isStopped = true
        RunnerEvents.updateState(RunnerEvents.STATE_STOPPED)
        RunnerEvents.addLog(reason)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ThreadFlow:RunnerWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 12 hours max
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ThreadFlow Auto Commenter",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi status eksekusi auto comment background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String, current: Int, total: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ThreadFlow Affiliate Runner")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_nav_runner)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotificationText(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text, 0, 0))
    }

    override fun onDestroy() {
        super.onDestroy()
        isStopped = true
        serviceJob.cancel()
        releaseWakeLock()
        RunnerEvents.updateState(RunnerEvents.STATE_IDLE)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
