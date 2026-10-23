package com.apadev.memoryfix.ads

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

object AdManager {

    // Official Google AdMob sample test IDs
    private const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
    private const val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"
    private const val TEST_REWARDED_ID = "ca-app-pub-3940256099942544/5224354917"

    private var interstitialAd: InterstitialAd? = null
    private var isAdLoading = false

    private var rewardedAd: RewardedAd? = null
    private var isRewardedLoading = false

    fun init(context: Context) {
        MobileAds.initialize(context) {}
        loadInterstitial(context)
        loadRewarded(context)
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

    fun loadRewarded(context: Context) {
        if (rewardedAd != null || isRewardedLoading) return
        isRewardedLoading = true

        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(
            context,
            TEST_REWARDED_ID,
            adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isRewardedLoading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    isRewardedLoading = false
                }
            }
        )
    }

    fun isRewardedAdReady(): Boolean = rewardedAd != null

    fun showRewarded(
        activity: Activity,
        onRewardEarned: () -> Unit,
        onDismissed: () -> Unit
    ) {
        val currentAd = rewardedAd
        if (currentAd == null || com.apadev.memoryfix.BuildConfig.DEBUG) {
            // In debug mode or if ad not yet cached, grant reward so testing / user is never blocked
            loadRewarded(activity)
            activity.runOnUiThread {
                onRewardEarned()
                onDismissed()
            }
            return
        }

        var rewardGranted = false

        currentAd.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadRewarded(activity)
                activity.runOnUiThread {
                    if (rewardGranted) {
                        onRewardEarned()
                    }
                    onDismissed()
                }
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                loadRewarded(activity)
                activity.runOnUiThread {
                    // Fallback: grant reward gracefully if ad failed to display
                    onRewardEarned()
                    onDismissed()
                }
            }
        }

        currentAd.show(activity) { _ ->
            rewardGranted = true
        }
    }
}
