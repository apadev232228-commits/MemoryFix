package com.apadev.memoryfix.billing

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BillingManager(private val context: Context) : PurchasesUpdatedListener {

    companion object {
        const val PRODUCT_ID_PRO = "memoryfix_pro_lifetime"
        private const val PREFS_NAME = "memoryfix_billing"
        private const val KEY_IS_PRO = "key_is_pro"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _isPro = MutableStateFlow(prefs.getBoolean(KEY_IS_PRO, false))
    val isPro: StateFlow<Boolean> = _isPro

    private var productDetailsMap = mutableMapOf<String, ProductDetails>()

    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun startConnection(onReady: (() -> Unit)? = null) {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPurchases()
                    queryProductDetails(onReady)
                }
            }

            override fun onBillingServiceDisconnected() {
                // Will retry on next call
            }
        })
    }

    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var hasPro = false
                for (purchase in purchases) {
                    if (purchase.products.contains(PRODUCT_ID_PRO) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    ) {
                        hasPro = true
                        if (!purchase.isAcknowledged) {
                            acknowledgePurchase(purchase)
                        }
                    }
                }
                if (!com.apadev.memoryfix.BuildConfig.DEBUG || hasPro) {
                    setProStatus(hasPro)
                }
            }
        }
    }

    private fun queryProductDetails(onComplete: (() -> Unit)? = null) {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_PRO)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult: BillingResult, result: QueryProductDetailsResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                result.productDetailsList?.forEach { details ->
                    productDetailsMap[details.productId] = details
                }
            }
            onComplete?.invoke()
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { }
    }

    fun launchBillingFlow(activity: Activity) {
        val details = productDetailsMap[PRODUCT_ID_PRO]
        if (details != null) {
            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(details)
                            .build()
                    )
                )
                .build()
            billingClient.launchBillingFlow(activity, billingFlowParams)
        } else {
            // Re-query and launch
            queryProductDetails {
                val updatedDetails = productDetailsMap[PRODUCT_ID_PRO]
                if (updatedDetails != null) {
                    val flowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(
                            listOf(
                                BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(updatedDetails)
                                    .build()
                            )
                        )
                        .build()
                    billingClient.launchBillingFlow(activity, flowParams)
                } else {
                    activity.runOnUiThread {
                        if (com.apadev.memoryfix.BuildConfig.DEBUG) {
                            setProStatus(true)
                            android.widget.Toast.makeText(
                                activity,
                                "Lifetime Pro Unlocked (Debug / Test Mode) ✓",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            android.widget.Toast.makeText(
                                activity,
                                "Google Play Store is currently unreachable. Please check connection.",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.products.contains(PRODUCT_ID_PRO)) {
                    acknowledgePurchase(purchase)
                    setProStatus(true)
                }
            }
        }
    }

    fun getFormattedPrice(): String {
        return productDetailsMap[PRODUCT_ID_PRO]?.oneTimePurchaseOfferDetails?.formattedPrice ?: "$4.99"
    }

    fun setProStatus(isPro: Boolean) {
        prefs.edit().putBoolean(KEY_IS_PRO, isPro).apply()
        _isPro.value = isPro
    }
}
