package com.apadev.memoryfix

import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apadev.memoryfix.ads.AdManager
import com.apadev.memoryfix.databinding.ActivityMainBinding
import com.apadev.memoryfix.ui.analysis.AnalysisFragment
import com.apadev.memoryfix.ui.home.HomeFragment
import com.apadev.memoryfix.ui.processing.ProcessingFragment
import com.apadev.memoryfix.ui.result.ResultFragment
import com.apadev.memoryfix.update.AppUpdateHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appUpdateHelper: AppUpdateHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ensure status bar and navigation bar icon contrast matches system theme
        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNightMode
            isAppearanceLightNavigationBars = !isNightMode
        }

        // Initialize Google Play In-App Updates and check in background
        appUpdateHelper = AppUpdateHelper(this)
        appUpdateHelper.register()
        appUpdateHelper.checkForAppUpdate(isUserInitiated = false)

        if (savedInstanceState == null) {
            navigateToHome()
        }

        val app = application as? MemoryFixApp
        lifecycleScope.launch {
            app?.billingManager?.isPro?.collectLatest { isPro ->
                AdManager.loadBanner(binding.bannerAdContainer, isPro)
            }
        }

        // Handle system back button to prevent screen merging
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
                when (currentFragment) {
                    is ProcessingFragment -> {
                        currentFragment.showCancelConfirmationDialog()
                    }
                    is ResultFragment -> {
                        // From result, go straight to home (skip analysis)
                        navigateToHome()
                    }
                    is AnalysisFragment -> {
                        // Normal back: pop to home
                        supportFragmentManager.popBackStack()
                    }
                    else -> {
                        // On home screen, finish the activity
                        finish()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        appUpdateHelper.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        appUpdateHelper.unregister()
    }

    /**
     * Public accessor to trigger update checks on demand (e.g. from Feedback / Settings)
     */
    fun checkForAppUpdate(isUserInitiated: Boolean = true) {
        appUpdateHelper.checkForAppUpdate(isUserInitiated)
    }

    fun navigateToHome() {
        // Clear entire back stack first
        supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.fade_scale_in, R.anim.fade_scale_out)
            .replace(R.id.fragmentContainer, HomeFragment())
            .commit()
    }

    fun navigateToAnalysis(folderUri: Uri) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            .replace(R.id.fragmentContainer, AnalysisFragment.newInstance(folderUri))
            .addToBackStack("analysis")
            .commit()
    }

    fun navigateToAnalysisWithZips(zipUris: List<Uri>) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            .replace(R.id.fragmentContainer, AnalysisFragment.newInstanceWithZips(zipUris))
            .addToBackStack("analysis")
            .commit()
    }

    fun navigateToProcessing() {
        // Clear the analysis from back stack so back from processing doesn't go to analysis
        supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.fade_scale_in, R.anim.fade_scale_out)
            .replace(R.id.fragmentContainer, ProcessingFragment())
            .commit()
    }

    fun navigateToResult(
        totalFixed: Int,
        photosFixed: Int,
        videosFixed: Int,
        overlaysMerged: Int = 0,
        failedCount: Int = 0,
        totalScanned: Int = 0
    ) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.fade_scale_in, R.anim.fade_scale_out)
            .replace(
                R.id.fragmentContainer,
                ResultFragment.newInstance(
                    totalFixed = totalFixed,
                    photosFixed = photosFixed,
                    videosFixed = videosFixed,
                    overlaysMerged = overlaysMerged,
                    failedCount = failedCount,
                    totalScanned = totalScanned
                )
            )
            .commit()
    }
}
