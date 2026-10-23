package com.apadev.memoryfix.update

import android.app.Activity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.apadev.memoryfix.BuildConfig
import com.apadev.memoryfix.R
import com.apadev.memoryfix.logging.AppLogger
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Manages Google Play In-App Updates for MemoryFix.
 *
 * Supports:
 * - Silent background flexible updates for standard improvements.
 * - Immediate full-screen updates for high-priority / breaking updates (priority >= 4).
 * - Automatic resume on Activity recreation / onResume.
 * - Manual "Check for updates" triggers with user feedback.
 */
class AppUpdateHelper(private val activity: ComponentActivity) {

    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(activity)
    private var updateLauncher: ActivityResultLauncher<IntentSenderRequest>

    private val installListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> {
                AppLogger.i("AppUpdateHelper", "Flexible update downloaded. Showing completion prompt.")
                showUpdateDownloadedSnackbar()
            }
            InstallStatus.DOWNLOADING -> {
                val bytes = state.bytesDownloaded()
                val total = state.totalBytesToDownload()
                if (total > 0) {
                    val progress = (bytes * 100 / total).toInt()
                    AppLogger.d("AppUpdateHelper", "Update downloading: $progress% ($bytes / $total)")
                }
            }
            InstallStatus.FAILED -> {
                AppLogger.w("AppUpdateHelper", "Update download failed. Error code: ${state.installErrorCode()}")
            }
            InstallStatus.CANCELED -> {
                AppLogger.d("AppUpdateHelper", "Update download canceled by user or system.")
            }
            else -> {
                AppLogger.d("AppUpdateHelper", "Install status: ${state.installStatus()}")
            }
        }
    }

    init {
        updateLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> {
                    AppLogger.i("AppUpdateHelper", "Update flow launched successfully.")
                }
                Activity.RESULT_CANCELED -> {
                    AppLogger.d("AppUpdateHelper", "User canceled the update flow.")
                }
                else -> {
                    AppLogger.w("AppUpdateHelper", "Update flow failed or denied. Code: ${result.resultCode}")
                }
            }
        }
    }

    /**
     * Start listening for install status updates.
     */
    fun register() {
        appUpdateManager.registerListener(installListener)
    }

    /**
     * Unregister to avoid memory leaks.
     */
    fun unregister() {
        try {
            appUpdateManager.unregisterListener(installListener)
        } catch (e: Exception) {
            AppLogger.w("AppUpdateHelper", "Failed to unregister install listener: ${e.message}")
        }
    }

    /**
     * Check if an update is available from the Google Play Store.
     *
     * @param isUserInitiated true when user taps "Check for Updates" manually.
     */
    fun checkForAppUpdate(isUserInitiated: Boolean = false) {
        AppLogger.d("AppUpdateHelper", "Checking for app update (userInitiated=$isUserInitiated)...")

        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            handleUpdateInfo(appUpdateInfo, isUserInitiated)
        }.addOnFailureListener { e ->
            AppLogger.w("AppUpdateHelper", "Failed to check for app update: ${e.message}")
            if (isUserInitiated) {
                Toast.makeText(
                    activity,
                    "Couldn't check for updates. Please try again later.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun handleUpdateInfo(appUpdateInfo: AppUpdateInfo, isUserInitiated: Boolean) {
        val availability = appUpdateInfo.updateAvailability()
        val installStatus = appUpdateInfo.installStatus()

        AppLogger.d(
            "AppUpdateHelper",
            "Update info: availability=$availability, installStatus=$installStatus, priority=${appUpdateInfo.updatePriority()}"
        )

        if (installStatus == InstallStatus.DOWNLOADED) {
            showUpdateDownloadedSnackbar()
            return
        }

        if (availability == UpdateAvailability.UPDATE_AVAILABLE) {
            val priority = appUpdateInfo.updatePriority()
            val isHighPriority = priority >= 4

            val updateType = if (isHighPriority && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                AppUpdateType.IMMEDIATE
            } else if (appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                AppUpdateType.FLEXIBLE
            } else if (appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                AppUpdateType.IMMEDIATE
            } else {
                null
            }

            if (updateType != null) {
                AppLogger.i("AppUpdateHelper", "Starting update flow of type $updateType...")
                try {
                    val options = AppUpdateOptions.defaultOptions(updateType)
                    appUpdateManager.startUpdateFlowForResult(appUpdateInfo, updateLauncher, options)
                } catch (e: Exception) {
                    AppLogger.e("AppUpdateHelper", "Failed to launch update flow", e)
                }
            } else {
                AppLogger.w("AppUpdateHelper", "Update available but neither Flexible nor Immediate allowed.")
            }
        } else if (availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
            // Resume in-progress immediate update
            if (appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        updateLauncher,
                        AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE)
                    )
                } catch (e: Exception) {
                    AppLogger.e("AppUpdateHelper", "Failed to resume immediate update flow", e)
                }
            }
        } else if (isUserInitiated) {
            Toast.makeText(
                activity,
                "MemoryFix is up to date (v${BuildConfig.VERSION_NAME})",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Call inside Activity's onResume to catch completed flexible downloads or in-progress updates.
     */
    fun onResume() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                showUpdateDownloadedSnackbar()
            } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                if (appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    try {
                        appUpdateManager.startUpdateFlowForResult(
                            appUpdateInfo,
                            updateLauncher,
                            AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE)
                        )
                    } catch (e: Exception) {
                        AppLogger.e("AppUpdateHelper", "Failed to resume immediate update on onResume", e)
                    }
                }
            }
        }
    }

    private fun showUpdateDownloadedSnackbar() {
        val rootView = activity.findViewById<android.view.View>(android.R.id.content) ?: return
        Snackbar.make(
            rootView,
            "An update for MemoryFix is ready to install.",
            Snackbar.LENGTH_INDEFINITE
        ).setAction("RESTART") {
            appUpdateManager.completeUpdate()
        }.show()
    }
}
