package com.fajar.threadflow.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.fajar.threadflow.R
import com.fajar.threadflow.data.AppPreferences
import com.fajar.threadflow.data.DatabaseHelper
import com.fajar.threadflow.databinding.FragmentSettingsBinding
import com.fajar.threadflow.network.AiClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private lateinit var db: DatabaseHelper
    private val aiClient = AiClient()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        prefs = AppPreferences(ctx)
        db = DatabaseHelper.getInstance(ctx)

        setupUI()
        updateAccountStatus()
    }

    private fun setupUI() {
        binding.etAiBaseUrl.setText(prefs.aiBaseUrl)
        binding.etAiApiKey.setText(prefs.aiApiKey)
        binding.etAiModel.setText(prefs.aiModel)

        binding.btnLoginThreads.setOnClickListener {
            showLoginDialog()
        }

        binding.btnLogoutThreads.setOnClickListener {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            prefs.clearThreadsSession()
            updateAccountStatus()
            (activity as? MainActivity)?.updateHeaderAccount()
            Toast.makeText(context, "Berhasil logout dari Threads", Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveAi.setOnClickListener {
            saveAiConfig(true)
        }

        binding.btnTestAi.setOnClickListener {
            saveAiConfig(false)
            testAiConnection()
        }

        binding.btnClearReports.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Hapus Semua Laporan?")
                .setMessage("Tindakan ini akan menghapus seluruh catatan riwayat komentar secara permanen.")
                .setPositiveButton("Hapus") { _, _ ->
                    db.clearAllReports()
                    Toast.makeText(context, "Riwayat laporan berhasil dihapus", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun updateAccountStatus() {
        if (_binding == null) return
        if (prefs.isThreadsLoggedIn) {
            val user = if (prefs.threadsUsername.isNotBlank()) "@${prefs.threadsUsername}" else "Terkoneksi"
            binding.tvAccountDetail.text = "Sesi Aktif: $user\nCookie sesi tersimpan aman. Tidak perlu login ulang."
            binding.btnLoginThreads.text = "Ganti Akun"
            binding.btnLogoutThreads.visibility = View.VISIBLE
        } else {
            binding.tvAccountDetail.text = "Belum login akun Threads. Silakan login untuk memulai auto-comment."
            binding.btnLoginThreads.text = "Login Threads"
            binding.btnLogoutThreads.visibility = View.GONE
        }
    }

    private fun saveAiConfig(showToast: Boolean) {
        prefs.aiBaseUrl = binding.etAiBaseUrl.text?.toString()?.trim() ?: "https://api.openai.com/v1"
        prefs.aiApiKey = binding.etAiApiKey.text?.toString()?.trim() ?: ""
        prefs.aiModel = binding.etAiModel.text?.toString()?.trim() ?: "gpt-4o-mini"

        if (showToast) {
            Toast.makeText(context, "Pengaturan AI berhasil disimpan", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testAiConnection() {
        val baseUrl = prefs.aiBaseUrl
        val apiKey = prefs.aiApiKey
        val model = prefs.aiModel

        val progress = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Menguji Koneksi AI")
            .setMessage("Menghubungi endpoint: $baseUrl\nModel: $model...")
            .setCancelable(false)
            .create()
        progress.show()

        lifecycleScope.launch {
            val res = aiClient.testConnection(baseUrl, apiKey, model)
            progress.dismiss()

            if (res.isSuccess) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Koneksi Sukses!")
                    .setMessage(res.getOrThrow())
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Koneksi Gagal")
                    .setMessage("Error: ${res.exceptionOrNull()?.message}")
                    .setPositiveButton("Tutup", null)
                    .show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showLoginDialog() {
        val dialog = Dialog(requireContext(), android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_login_webview)

        val webView = dialog.findViewById<WebView>(R.id.webViewLogin)
        val progress = dialog.findViewById<ProgressBar>(R.id.progressWeb)
        val btnClose = dialog.findViewById<Button>(R.id.btnCloseDialog)

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies != null && cookies.contains("sessionid=")) {
                    prefs.threadsCookie = cookies
                    prefs.isThreadsLoggedIn = true

                    val dsUserId = Regex("ds_user_id=([0-9]+)").find(cookies)?.groupValues?.get(1) ?: ""
                    prefs.threadsUserId = dsUserId
                    prefs.threadsUsername = "user_$dsUserId"

                    Toast.makeText(context, "Login Threads Berhasil!", Toast.LENGTH_SHORT).show()
                    updateAccountStatus()
                    (activity as? MainActivity)?.updateHeaderAccount()
                    dialog.dismiss()
                }
            }
        }

        webView.loadUrl("https://www.threads.net/login")
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
