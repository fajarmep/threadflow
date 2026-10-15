package com.fajar.threadflow.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.fajar.threadflow.R
import com.fajar.threadflow.data.AppPreferences
import com.fajar.threadflow.databinding.FragmentCampaignBinding
import com.fajar.threadflow.network.AiClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class CampaignFragment : Fragment() {

    private var _binding: FragmentCampaignBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private val aiClient = AiClient()
    private val selectedMediaUris = mutableListOf<Uri>()

    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            selectedMediaUris.clear()
            if (uris != null && uris.isNotEmpty()) {
                selectedMediaUris.addAll(uris)
                binding.tvMediaStatus.text = getString(R.string.campaign_media_count, uris.size)
                prefs.campaignMediaUris = uris.joinToString(",") { it.toString() }
            } else {
                binding.tvMediaStatus.text = getString(R.string.campaign_media_empty)
                prefs.campaignMediaUris = ""
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCampaignBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        prefs = AppPreferences(ctx)

        setupForm()
    }

    private fun setupForm() {
        binding.etProductDesc.setText(prefs.campaignDescription)
        binding.etAffiliateLink.setText(prefs.campaignAffiliateLink)

        val tones = if (prefs.appLanguage == "en") {
            listOf(
                "Educational (Recommended)",
                "Casual & Friendly",
                "Storytelling Experience",
                "Solution-Oriented & Direct"
            )
        } else {
            listOf(
                "Edukatif (Rekomendasi)",
                "Santai & Ramah",
                "Storytelling Pengalaman",
                "Solutif To-The-Point"
            )
        }
        val toneAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, tones)
        binding.actvTone.setAdapter(toneAdapter)

        val savedTone = prefs.campaignTone
        val activeIndex = tones.indexOfFirst { it.take(8).equals(savedTone.take(8), ignoreCase = true) }.coerceAtLeast(0)
        binding.actvTone.setText(tones[activeIndex], false)

        if (prefs.campaignMediaUris.isNotBlank()) {
            val count = prefs.campaignMediaUris.split(",").size
            binding.tvMediaStatus.text = getString(R.string.campaign_media_prev_count, count)
        }

        binding.btnPickMedia.setOnClickListener {
            pickMediaLauncher.launch("*/*")
        }

        binding.btnSaveCampaign.setOnClickListener {
            saveCampaign(true)
        }

        binding.btnSimulateAi.setOnClickListener {
            runSimulation()
        }
    }

    private fun saveCampaign(showToast: Boolean) {
        prefs.campaignDescription = binding.etProductDesc.text?.toString()?.trim() ?: ""
        prefs.campaignAffiliateLink = binding.etAffiliateLink.text?.toString()?.trim() ?: ""
        val selectedTone = binding.actvTone.text?.toString()?.trim() ?: "Edukatif"
        prefs.campaignTone = selectedTone

        if (showToast) {
            Toast.makeText(context, getString(R.string.campaign_toast_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun runSimulation() {
        val desc = binding.etProductDesc.text?.toString()?.trim() ?: ""
        val link = binding.etAffiliateLink.text?.toString()?.trim() ?: ""
        val tone = binding.actvTone.text?.toString()?.trim() ?: "Edukatif"

        if (desc.isBlank()) {
            Toast.makeText(context, getString(R.string.campaign_toast_fill_desc), Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.campaign_sim_title))
            .setMessage(getString(R.string.campaign_sim_connecting))
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val dummyPost = "Sering banget ngerasa capek dan gak produktif seharian, ada tips gak ya buat ngatasin ini?"
            val dummyAuthor = "diskusi_hari_ini"

            val result = aiClient.generateTwoTierComments(
                baseUrl = prefs.aiBaseUrl,
                apiKey = prefs.aiApiKey,
                model = prefs.aiModel,
                postContent = dummyPost,
                author = dummyAuthor,
                productDesc = desc,
                affiliateLink = link.ifBlank { "https://shopee.co.id/example-affiliate" },
                tone = tone
            )

            progressDialog.dismiss()

            if (result.isSuccess) {
                val pair = result.getOrThrow()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.campaign_sim_result_title))
                    .setMessage(
                        "📌 KOMENTAR 1 (Value/Insight):\n\"${pair.first}\"\n\n" +
                        "🔗 KOMENTAR 2 (CTA + Link):\n\"${pair.second}\""
                    )
                    .setPositiveButton(getString(R.string.dialog_btn_ok), null)
                    .show()
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.campaign_sim_failed_title))
                    .setMessage(getString(R.string.campaign_sim_failed_msg, result.exceptionOrNull()?.message ?: "Unknown error"))
                    .setPositiveButton(getString(R.string.dialog_btn_close), null)
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
