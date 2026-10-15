package com.fajar.threadflow.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.fajar.threadflow.data.AppPreferences
import com.fajar.threadflow.data.DatabaseHelper
import com.fajar.threadflow.databinding.FragmentRunnerBinding
import com.fajar.threadflow.service.CommentRunnerService
import com.fajar.threadflow.service.RunnerEvents

class RunnerFragment : Fragment() {

    private var _binding: FragmentRunnerBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: AppPreferences
    private lateinit var db: DatabaseHelper
    private lateinit var logAdapter: LogAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRunnerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        prefs = AppPreferences(ctx)
        db = DatabaseHelper.getInstance(ctx)

        setupUI()
        observeEvents()
    }

    private fun setupUI() {
        binding.etDelayMin.setText(prefs.delayMinSeconds.toString())
        binding.etDelayMax.setText(prefs.delayMaxSeconds.toString())
        binding.etDailyCap.setText(prefs.dailySafetyCap.toString())

        logAdapter = LogAdapter(emptyList())
        binding.rvLogs.layoutManager = LinearLayoutManager(context)
        binding.rvLogs.adapter = logAdapter

        binding.btnClearLogs.setOnClickListener {
            RunnerEvents.clearLogs()
        }

        binding.btnStart.setOnClickListener {
            saveControls()
            val intent = Intent(requireContext(), CommentRunnerService::class.java).apply {
                action = CommentRunnerService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(requireContext(), intent)
            } else {
                requireContext().startService(intent)
            }
            Toast.makeText(context, "Runner dimulai di background", Toast.LENGTH_SHORT).show()
        }

        binding.btnPause.setOnClickListener {
            val currentState = RunnerEvents.state.value
            val intent = Intent(requireContext(), CommentRunnerService::class.java).apply {
                action = if (currentState == RunnerEvents.STATE_PAUSED) {
                    CommentRunnerService.ACTION_RESUME
                } else {
                    CommentRunnerService.ACTION_PAUSE
                }
            }
            requireContext().startService(intent)
        }

        binding.btnStop.setOnClickListener {
            val intent = Intent(requireContext(), CommentRunnerService::class.java).apply {
                action = CommentRunnerService.ACTION_STOP
            }
            requireContext().startService(intent)
            Toast.makeText(context, "Runner dihentikan", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveControls() {
        val minD = binding.etDelayMin.text?.toString()?.toIntOrNull() ?: 60
        val maxD = binding.etDelayMax.text?.toString()?.toIntOrNull() ?: 180
        val cap = binding.etDailyCap.text?.toString()?.toIntOrNull() ?: 40

        prefs.delayMinSeconds = minD.coerceAtLeast(10)
        prefs.delayMaxSeconds = maxD.coerceAtLeast(prefs.delayMinSeconds)
        prefs.dailySafetyCap = cap.coerceAtLeast(1)
    }

    private fun observeEvents() {
        RunnerEvents.state.observe(viewLifecycleOwner) { state ->
            binding.chipRunnerStatus.text = state
            when (state) {
                RunnerEvents.STATE_RUNNING -> {
                    binding.chipRunnerStatus.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#10B981"))
                    binding.btnStart.isEnabled = false
                    binding.btnPause.isEnabled = true
                    binding.btnPause.text = "JEDA"
                    binding.btnStop.isEnabled = true
                }
                RunnerEvents.STATE_PAUSED -> {
                    binding.chipRunnerStatus.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                    binding.btnStart.isEnabled = false
                    binding.btnPause.isEnabled = true
                    binding.btnPause.text = "LANJUT"
                    binding.btnStop.isEnabled = true
                }
                else -> {
                    binding.chipRunnerStatus.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#64748B"))
                    binding.btnStart.isEnabled = true
                    binding.btnPause.isEnabled = false
                    binding.btnPause.text = "JEDA"
                    binding.btnStop.isEnabled = false
                }
            }
            updateDailyInfo()
        }

        RunnerEvents.logs.observe(viewLifecycleOwner) { logs ->
            logAdapter.updateLogs(logs)
        }

        RunnerEvents.progress.observe(viewLifecycleOwner) { (cur, total) ->
            if (total > 0) {
                binding.progressBarRunner.max = total
                binding.progressBarRunner.progress = cur
                binding.tvProgressText.text = "Antrean: $cur / $total post"
            } else {
                binding.progressBarRunner.progress = 0
                binding.tvProgressText.text = "Antrean: 0 / 0 post"
            }
            updateDailyInfo()
        }
    }

    private fun updateDailyInfo() {
        if (_binding == null) return
        val today = db.getTodayReportCount()
        binding.tvDailyCapInfo.text = "Hari ini: $today / ${prefs.dailySafetyCap} post"
    }

    override fun onResume() {
        super.onResume()
        updateDailyInfo()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        saveControls()
        _binding = null
    }
}
