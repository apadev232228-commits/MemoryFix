package com.apadev.memoryfix.ads

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

object AdManager {

    // Official Google AdMob sample test IDs
    private const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
    private const val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"

    private var interstitialAd: InterstitialAd? = null
    private var isAdLoading = false

    fun init(context: Context) {
        MobileAds.initialize(context) {}
        loadInterstitial(context)
    }

    fun loadBanner(container: ViewGroup, isPro: Boolean) {
        if (isPro) {
            container.removeAllViews()
            container.visibility = android.view.View.GONE
            return
        }

        container.removeAllViews()
        container.visibility = android.view.View.VISIBLE

        val adView = AdView(container.context).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = TEST_BANNER_ID
        }
        container.addView(adView)
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)
    }

    fun loadInterstitial(context: Context) {
        if (interstitialAd != null || isAdLoading) return
        isAdLoading = true

        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            context,
            TEST_INTERSTITIAL_ID,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isAdLoading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    isAdLoading = false
                }
            }
        )
    }

    fun showInterstitial(activity: Activity, isPro: Boolean, onDismissed: () -> Unit) {
        if (isPro || interstitialAd == null) {
            onDismissed()
            return
        }

        interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                loadInterstitial(activity)
                onDismissed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAd = null
                loadInterstitial(activity)
                onDismissed()
            }
        }

        interstitialAd?.show(activity)
    }
}
