package com.fajar.threadflow.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.fajar.threadflow.R
import com.fajar.threadflow.data.AppPreferences
import com.fajar.threadflow.databinding.ActivityMainBinding
import com.fajar.threadflow.util.LocaleHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences

    private val requestNotificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        setupViewPager()
        setupBottomNav()
        updateHeaderAccount()
        checkPermissions()
    }

    private fun setupViewPager() {
        val fragments: List<Fragment> = listOf(
            ScraperFragment(),
            CampaignFragment(),
            RunnerFragment(),
            ReportFragment(),
            SettingsFragment()
        )

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = fragments.size
            override fun createFragment(position: Int): Fragment = fragments[position]
        }

        binding.viewPager.isUserInputEnabled = false
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                when (position) {
                    0 -> binding.bottomNav.selectedItemId = R.id.nav_scraper
                    1 -> binding.bottomNav.selectedItemId = R.id.nav_campaign
                    2 -> binding.bottomNav.selectedItemId = R.id.nav_runner
                    3 -> binding.bottomNav.selectedItemId = R.id.nav_report
                    4 -> binding.bottomNav.selectedItemId = R.id.nav_settings
                }
            }
        })
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_scraper -> binding.viewPager.setCurrentItem(0, false)
                R.id.nav_campaign -> binding.viewPager.setCurrentItem(1, false)
                R.id.nav_runner -> binding.viewPager.setCurrentItem(2, false)
                R.id.nav_report -> binding.viewPager.setCurrentItem(3, false)
                R.id.nav_settings -> binding.viewPager.setCurrentItem(4, false)
            }
            true
        }

        binding.chipAccountStatus.setOnClickListener {
            binding.viewPager.setCurrentItem(4, false)
        }
    }

    fun updateHeaderAccount() {
        if (prefs.isThreadsLoggedIn) {
            val user = if (prefs.threadsUsername.isNotBlank()) "@${prefs.threadsUsername}" else "@threads"
            binding.chipAccountStatus.text = getString(R.string.account_logged_in_prefix) + user
            binding.chipAccountStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            binding.chipAccountStatus.text = getString(R.string.account_not_logged_in)
            binding.chipAccountStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
