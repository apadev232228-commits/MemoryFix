package com.apadev.memoryfix

import android.net.Uri
import android.os.Bundle
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
    }

    fun navigateToHome() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, HomeFragment())
            .commit()
    }

    fun navigateToAnalysis(folderUri: Uri) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, AnalysisFragment.newInstance(folderUri))
            .addToBackStack(null)
            .commit()
    }

    fun navigateToProcessing() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, ProcessingFragment())
            .commit()
    }

    fun navigateToResult(totalFixed: Int) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, ResultFragment.newInstance(totalFixed))
            .commit()
    }
}
