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

    // Real Google AdMob Production IDs
    private const val PROD_BANNER_ID = "ca-app-pub-6736930312036077/6919778825"
    private const val PROD_INTERSTITIAL_ID = "ca-app-pub-6736930312036077/3060424863"
    private const val PROD_REWARDED_ID = "ca-app-pub-6736930312036077/3842315659"

    private val bannerAdUnitId: String
        get() = if (com.apadev.memoryfix.BuildConfig.DEBUG) TEST_BANNER_ID else PROD_BANNER_ID

    private val interstitialAdUnitId: String
        get() = if (com.apadev.memoryfix.BuildConfig.DEBUG) TEST_INTERSTITIAL_ID else PROD_INTERSTITIAL_ID

    private val rewardedAdUnitId: String
        get() = if (com.apadev.memoryfix.BuildConfig.DEBUG) TEST_REWARDED_ID else PROD_REWARDED_ID

    private var interstitialAd: InterstitialAd? = null
    private var isAdLoading = false

    private var rewardedAd: RewardedAd? = null
    private var isRewardedLoading = false
    private val rewardedLoadCallbacks = mutableListOf<(RewardedAd?) -> Unit>()

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
            adUnitId = bannerAdUnitId
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
            interstitialAdUnitId,
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

    fun loadRewarded(context: Context, onLoaded: ((RewardedAd?) -> Unit)? = null) {
        if (rewardedAd != null) {
            onLoaded?.invoke(rewardedAd)
            return
        }
        if (onLoaded != null) {
            rewardedLoadCallbacks.add(onLoaded)
        }
        if (isRewardedLoading) return
        isRewardedLoading = true

        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(
            context.applicationContext,
            rewardedAdUnitId,
            adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isRewardedLoading = false
                    val callbacks = ArrayList(rewardedLoadCallbacks)
                    rewardedLoadCallbacks.clear()
                    callbacks.forEach { it.invoke(ad) }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    isRewardedLoading = false
                    val callbacks = ArrayList(rewardedLoadCallbacks)
                    rewardedLoadCallbacks.clear()
                    callbacks.forEach { it.invoke(null) }
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
        if (currentAd != null) {
            showLoadedRewarded(activity, currentAd, onRewardEarned, onDismissed)
            return
        }

        val progressDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setMessage("Loading sponsor ad...")
            .setCancelable(false)
            .create()

        try {
            progressDialog.show()
        } catch (_: Exception) {}

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var isHandled = false

        val timeoutRunnable = Runnable {
            if (!isHandled) {
                isHandled = true
                try {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                } catch (_: Exception) {}
                android.widget.Toast.makeText(
                    activity,
                    "Ad could not be loaded. Please check your internet connection.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                onDismissed()
            }
        }
        handler.postDelayed(timeoutRunnable, 5000)

        loadRewarded(activity) { loadedAd ->
            activity.runOnUiThread {
                if (!isHandled) {
                    isHandled = true
                    handler.removeCallbacks(timeoutRunnable)
                    try {
                        if (progressDialog.isShowing) progressDialog.dismiss()
                    } catch (_: Exception) {}

                    if (loadedAd != null) {
                        showLoadedRewarded(activity, loadedAd, onRewardEarned, onDismissed)
                    } else {
                        android.widget.Toast.makeText(
                            activity,
                            "Failed to load sponsor ad. Please try again.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        onDismissed()
                    }
                }
            }
        }
    }

    private fun showLoadedRewarded(
        activity: Activity,
        ad: RewardedAd,
        onRewardEarned: () -> Unit,
        onDismissed: () -> Unit
    ) {
        var rewardEarned = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadRewarded(activity)
                activity.runOnUiThread {
                    if (rewardEarned) {
                        onRewardEarned()
                    }
                    onDismissed()
                }
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                loadRewarded(activity)
                activity.runOnUiThread {
                    android.widget.Toast.makeText(
                        activity,
                        "Failed to display sponsor video.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    onDismissed()
                }
            }
        }

        ad.show(activity) { _ ->
            rewardEarned = true
        }
    }
}
