package com.apadev.memoryfix

import android.app.Application
import com.apadev.memoryfix.ads.AdManager
import com.apadev.memoryfix.billing.BillingManager

class MemoryFixApp : Application() {

    lateinit var billingManager: BillingManager
        private set

    override fun onCreate() {
        super.onCreate()
        
        // Initialize AdMob
        AdManager.init(this)

        // Initialize Billing
        billingManager = BillingManager(this).apply {
            startConnection()
        }
    }
}
