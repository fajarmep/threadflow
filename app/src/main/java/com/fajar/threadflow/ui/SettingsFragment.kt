package com.fajar.threadflow.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.fajar.threadflow.R
import com.fajar.threadflow.data.AppPreferences
import com.fajar.threadflow.data.DatabaseHelper
import com.fajar.threadflow.databinding.FragmentSettingsBinding
import com.fajar.threadflow.network.AiClient
import com.fajar.threadflow.util.LocaleHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
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
        displayAppVersion()
    }

    private fun setupUI() {
        binding.etAiBaseUrl.setText(prefs.aiBaseUrl)
        binding.etAiApiKey.setText(prefs.aiApiKey)
        binding.etAiModel.setText(prefs.aiModel)

        // Language setup
        if (prefs.appLanguage == "en") {
            binding.rbEnglish.isChecked = true
        } else {
            binding.rbIndonesian.isChecked = true
        }

        binding.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val selectedLang = if (checkedId == R.id.rbEnglish) "en" else "id"
            if (selectedLang != prefs.appLanguage) {
                prefs.appLanguage = selectedLang
                LocaleHelper.setLocale(requireContext(), selectedLang)
                activity?.recreate()
            }
        }

        binding.btnLoginThreads.setOnClickListener {
            showLoginDialog()
        }

        binding.btnLogoutThreads.setOnClickListener {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            prefs.clearThreadsSession()
            updateAccountStatus()
            (activity as? MainActivity)?.updateHeaderAccount()
            Toast.makeText(context, getString(R.string.settings_toast_logout), Toast.LENGTH_SHORT).show()
        }

        binding.tvAccountDetail.setOnClickListener {
            if (prefs.isThreadsLoggedIn) {
                showEditUsernameDialog()
            }
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
                .setTitle(getString(R.string.settings_dialog_clear_title))
                .setMessage(getString(R.string.settings_dialog_clear_msg))
                .setPositiveButton(getString(R.string.dialog_btn_delete)) { _, _ ->
                    db.clearAllReports()
                    Toast.makeText(context, getString(R.string.settings_toast_cleared), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.dialog_btn_cancel), null)
                .show()
        }
    }

    private fun showEditUsernameDialog() {
        val input = EditText(requireContext()).apply {
            setText(prefs.threadsUsername)
            hint = getString(R.string.settings_edit_username_hint)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_edit_username_title))
            .setView(input)
            .setPositiveButton(getString(R.string.dialog_btn_ok)) { _, _ ->
                val newName = input.text.toString().trim().removePrefix("@")
                if (newName.isNotBlank()) {
                    prefs.threadsUsername = newName
                    updateAccountStatus()
                    (activity as? MainActivity)?.updateHeaderAccount()
                }
            }
            .setNegativeButton(getString(R.string.dialog_btn_cancel), null)
            .show()
    }

    private fun displayAppVersion() {
        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val verName = pInfo.versionName
            val verCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else pInfo.versionCode.toLong()
            binding.tvAppVersion.text = "ThreadFlow v$verName (Build $verCode)\nAuto Affiliate & Threads Viral Booster"
        } catch (_: Exception) {
            binding.tvAppVersion.text = "ThreadFlow v1.0.5\nAuto Affiliate & Threads Viral Booster"
        }
    }

    private fun updateAccountStatus() {
        if (_binding == null) return
        if (prefs.isThreadsLoggedIn) {
            val user = if (prefs.threadsUsername.isNotBlank()) "@${prefs.threadsUsername}" else "@threads"
            binding.tvAccountDetail.text = getString(R.string.settings_session_active, user)
            binding.btnLoginThreads.text = getString(R.string.settings_switch_account)
            binding.btnLogoutThreads.visibility = View.VISIBLE
        } else {
            binding.tvAccountDetail.text = getString(R.string.settings_session_empty)
            binding.btnLoginThreads.text = getString(R.string.settings_login_threads)
            binding.btnLogoutThreads.visibility = View.GONE
        }
    }

    private fun saveAiConfig(showToast: Boolean) {
        prefs.aiBaseUrl = binding.etAiBaseUrl.text?.toString()?.trim() ?: "https://api.openai.com/v1"
        prefs.aiApiKey = binding.etAiApiKey.text?.toString()?.trim() ?: ""
        prefs.aiModel = binding.etAiModel.text?.toString()?.trim() ?: "gpt-4o-mini"

        if (showToast) {
            Toast.makeText(context, getString(R.string.settings_toast_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun testAiConnection() {
        val baseUrl = prefs.aiBaseUrl
        val apiKey = prefs.aiApiKey
        val model = prefs.aiModel

        val progress = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_test_testing_title))
            .setMessage(getString(R.string.settings_test_testing_msg, baseUrl, model))
            .setCancelable(false)
            .create()
        progress.show()

        lifecycleScope.launch {
            val res = aiClient.testConnection(baseUrl, apiKey, model)
            progress.dismiss()

            if (res.isSuccess) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.settings_test_success_title))
                    .setMessage(res.getOrThrow())
                    .setPositiveButton(getString(R.string.dialog_btn_ok), null)
                    .show()
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.settings_test_failed_title))
                    .setMessage("Error: ${res.exceptionOrNull()?.message}")
                    .setPositiveButton(getString(R.string.dialog_btn_close), null)
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
                val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                if (cookies.contains("sessionid=")) {
                    prefs.threadsCookie = cookies
                    prefs.isThreadsLoggedIn = true

                    val dsUserId = Regex("ds_user_id=([0-9]+)").find(cookies)?.groupValues?.get(1) ?: ""
                    prefs.threadsUserId = dsUserId

                    // Extract actual username from DOM
                    val js = """
                        (function() {
                            var links = document.querySelectorAll('a[href^="/@"]');
                            for (var i = 0; i < links.length; i++) {
                                var h = links[i].getAttribute('href') || '';
                                var m = h.match(/^\/@([a-zA-Z0-9._]+)$/);
                                if (m && m[1] !== 'home' && m[1] !== 'search' && m[1] !== 'activity') {
                                    return m[1];
                                }
                            }
                            return '';
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(js) { res ->
                        val cleaned = res?.replace("\"", "")?.trim() ?: ""
                        if (cleaned.isNotBlank() && cleaned != "null") {
                            prefs.threadsUsername = cleaned
                        } else if (prefs.threadsUsername.isBlank()) {
                            prefs.threadsUsername = dsUserId
                        }
                        updateAccountStatus()
                        (activity as? MainActivity)?.updateHeaderAccount()
                    }

                    Toast.makeText(context, "Login Threads Berhasil!", Toast.LENGTH_SHORT).show()
                    updateAccountStatus()
                    (activity as? MainActivity)?.updateHeaderAccount()
                    dialog.dismiss()
                }
            }
        }

        webView.loadUrl("https://www.threads.com/login")
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
