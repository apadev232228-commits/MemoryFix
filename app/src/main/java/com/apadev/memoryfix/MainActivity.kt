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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                        // Don't allow back during processing — user must tap Cancel
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

    fun navigateToHome() {
        // Clear entire back stack first
        supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, HomeFragment())
            .commit()
    }

    fun navigateToAnalysis(folderUri: Uri) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, AnalysisFragment.newInstance(folderUri))
            .addToBackStack("analysis")
            .commit()
    }

    fun navigateToAnalysisWithZips(zipUris: List<Uri>) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, AnalysisFragment.newInstanceWithZips(zipUris))
            .addToBackStack("analysis")
            .commit()
    }

    fun navigateToProcessing() {
        // Clear the analysis from back stack so back from processing doesn't go to analysis
        supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
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
