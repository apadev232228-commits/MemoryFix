package com.apadev.memoryfix

import android.app.Application
import com.apadev.memoryfix.ads.AdManager
import com.apadev.memoryfix.billing.BillingManager

class MemoryFixApp : Application() {

    lateinit var billingManager: BillingManager
        private set

    override fun onCreate() {
        super.onCreate()
        
        // Follow system theme automatically
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )

        // Initialize Logger
        com.apadev.memoryfix.logging.AppLogger.init(this)

        // Initialize AdMob
        AdManager.init(this)

        // Initialize Billing
        billingManager = BillingManager(this).apply {
            startConnection()
        }
    }
}
