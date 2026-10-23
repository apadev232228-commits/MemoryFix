package com.apadev.memoryfix.ui.analysis

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.apadev.memoryfix.MainActivity
import com.apadev.memoryfix.MemoryFixApp
import com.apadev.memoryfix.R
import com.apadev.memoryfix.ads.AdManager
import com.apadev.memoryfix.data.FullScanResult
import com.apadev.memoryfix.data.MediaScanner
import com.apadev.memoryfix.data.ScanProgress
import com.apadev.memoryfix.databinding.FragmentAnalysisBinding
import com.apadev.memoryfix.model.ScanStats
import com.apadev.memoryfix.ui.pro.ProUpgradeBottomSheet
import com.apadev.memoryfix.worker.ExportProcessWorker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.TimeZone

class AnalysisFragment : Fragment() {

    private var _binding: FragmentAnalysisBinding? = null
    private val binding get() = _binding!!

    private var folderUri: Uri? = null
    private var zipUris: List<Uri>? = null
    private var scanResult: FullScanResult? = null
    private var selectedTimezone: String = TimeZone.getDefault().id

    private var isGpsUnlockedViaAd: Boolean = false
    private var isDuplicatesUnlockedViaAd: Boolean = false

    private var pendingProcessingAction: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingProcessingAction?.invoke()
        pendingProcessingAction = null
    }

    private fun checkNotificationPermissionAndProceed(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingProcessingAction = action
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        action()
    }

    companion object {
        private const val ARG_FOLDER_URI = "ARG_FOLDER_URI"
        private const val ARG_ZIP_URIS = "ARG_ZIP_URIS"
        private const val FREE_TIER_LIMIT = 1500

        fun newInstance(uri: Uri): AnalysisFragment {
            return AnalysisFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_FOLDER_URI, uri)
                }
            }
        }

        fun newInstanceWithZips(uris: List<Uri>): AnalysisFragment {
            return AnalysisFragment().apply {
                arguments = Bundle().apply {
                    putParcelableArrayList(ARG_ZIP_URIS, ArrayList(uris))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            folderUri = arguments?.getParcelable(ARG_FOLDER_URI, Uri::class.java)
            zipUris = arguments?.getParcelableArrayList(ARG_ZIP_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            folderUri = arguments?.getParcelable(ARG_FOLDER_URI)
            @Suppress("DEPRECATION")
            zipUris = arguments?.getParcelableArrayList(ARG_ZIP_URIS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalysisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val isZipSource = !zipUris.isNullOrEmpty()
        if (folderUri == null && !isZipSource) return

        val app = activity?.application as? MemoryFixApp
        val billingManager = app?.billingManager

        AdManager.loadInterstitial(requireContext())
        AdManager.loadRewarded(requireContext())

        binding.layoutScanningPhase.visibility = View.VISIBLE
        binding.layoutReadyPhase.visibility = View.GONE
        binding.tvCurrentTimezone.text = selectedTimezone
        binding.tvScanningTitle.text = if (isZipSource) "Scanning Snapchat Archive" else "Scanning Export Folder"
        binding.tvFolderScanStatus.text = "Reading timestamps & matching timeline..."
        binding.tvLiveScanCounter.text = "Initializing scan..."
        binding.btnStartProcessing.isEnabled = false
        binding.btnStartProcessing.alpha = 0.5f

        // Observe Pro status
        viewLifecycleOwner.lifecycleScope.launch {
            billingManager?.isPro?.collectLatest { isPro ->
                updateProUi(isPro)
            }
        }

        binding.btnUnlockProBanner.setOnClickListener {
            ProUpgradeBottomSheet.newInstance().show(parentFragmentManager, ProUpgradeBottomSheet.TAG)
        }

        binding.btnChangeTimezone.setOnClickListener {
            showTimezoneDialog()
        }

        binding.switchEmbedLocation.setOnClickListener {
            val isPro = billingManager?.isPro?.value ?: false
            if (!isPro && !isGpsUnlockedViaAd) {
                if (binding.switchEmbedLocation.isChecked) {
                    showFeatureUnlockDialog(
                        title = "Unlock Smart GPS Locations",
                        message = "Smart GPS geotagging (including 30-min surrounding location inference) is a Pro feature.\n\nWatch a quick sponsor ad to unlock it for this export for free, or upgrade to Lifetime Pro.",
                        onUnlockViaAd = {
                            AdManager.showRewarded(requireActivity(), onRewardEarned = {
                                isGpsUnlockedViaAd = true
                                binding.badgeRewardGps.text = "UNLOCKED ✓"
                                binding.badgeRewardGps.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
                                binding.switchEmbedLocation.isChecked = true
                            }, onDismissed = {
                                if (!isGpsUnlockedViaAd) {
                                    binding.switchEmbedLocation.isChecked = false
                                }
                            })
                        },
                        onCancel = {
                            binding.switchEmbedLocation.isChecked = false
                        }
                    )
                }
            }
        }

        binding.switchSkipDuplicates.setOnClickListener {
            val isPro = billingManager?.isPro?.value ?: false
            if (!isPro && !isDuplicatesUnlockedViaAd) {
                if (binding.switchSkipDuplicates.isChecked) {
                    showFeatureUnlockDialog(
                        title = "Unlock Duplicate Cleaner",
                        message = "Smart duplicate cleaner is a Pro feature.\n\nWatch a quick sponsor ad to unlock it for this export for free, or upgrade to Lifetime Pro.",
                        onUnlockViaAd = {
                            AdManager.showRewarded(requireActivity(), onRewardEarned = {
                                isDuplicatesUnlockedViaAd = true
                                binding.badgeRewardDuplicates.text = "UNLOCKED ✓"
                                binding.badgeRewardDuplicates.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
                                binding.switchSkipDuplicates.isChecked = true
                                scanResult?.stats?.let { updateHeroStats(it) }
                            }, onDismissed = {
                                if (!isDuplicatesUnlockedViaAd) {
                                    binding.switchSkipDuplicates.isChecked = false
                                }
                                scanResult?.stats?.let { updateHeroStats(it) }
                            })
                        },
                        onCancel = {
                            binding.switchSkipDuplicates.isChecked = false
                            scanResult?.stats?.let { updateHeroStats(it) }
                        }
                    )
                } else {
                    scanResult?.stats?.let { updateHeroStats(it) }
                }
            } else {
                scanResult?.stats?.let { updateHeroStats(it) }
            }
        }

        binding.switchVideoOverlay.setOnClickListener {
            if (binding.switchVideoOverlay.isChecked) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Burn Stickers into Videos?")
                    .setMessage("Baking stickers into videos requires frame-by-frame re-encoding:\n\n• Processing takes much longer (~10–20 seconds per video).\n• Uses significantly more battery and device storage.\n• Video compression quality may be slightly reduced compared to original untouched videos.\n\nIf turned off, video stickers are still preserved alongside your videos.")
                    .setPositiveButton("Enable (Slower)") { _, _ -> }
                    .setNegativeButton("Keep Off (Recommended)") { _, _ ->
                        binding.switchVideoOverlay.isChecked = false
                    }
                    .setOnCancelListener {
                        binding.switchVideoOverlay.isChecked = false
                    }
                    .show()
            }
        }

        binding.btnStartProcessing.setOnClickListener {
            val stats = scanResult?.stats
            if (stats != null && hasMismatch(stats)) {
                showMismatchWarningDialog(stats)
            } else {
                startProcessing()
            }
        }

        // Start scanning with real-time live progress updates
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (isZipSource) {
                    MediaScanner.scanZipUris(requireContext(), zipUris!!, selectedTimezone) { progress ->
                        updateProgressUI(progress)
                    }
                } else {
                    MediaScanner.scanTree(requireContext(), folderUri!!, selectedTimezone) { progress ->
                        updateProgressUI(progress)
                    }
                }
            }
            scanResult = result
            populateStats(result)
        }
    }

    private fun updateProUi(isPro: Boolean) {
        if (_binding == null) return
        if (isPro) {
            binding.badgeRewardGps.visibility = View.GONE
            binding.badgeRewardDuplicates.visibility = View.GONE
            binding.cardLargeArchiveBanner.visibility = View.GONE
            binding.switchEmbedLocation.isChecked = true
            binding.switchSkipDuplicates.isChecked = true
        } else {
            val stats = scanResult?.stats
            if (stats != null && stats.totalMediaFiles > FREE_TIER_LIMIT) {
                binding.cardLargeArchiveBanner.visibility = View.VISIBLE
            }
            binding.badgeRewardGps.visibility = View.VISIBLE
            binding.badgeRewardDuplicates.visibility = View.VISIBLE

            binding.switchEmbedLocation.isChecked = isGpsUnlockedViaAd
            binding.switchSkipDuplicates.isChecked = isDuplicatesUnlockedViaAd

            if (isGpsUnlockedViaAd) {
                binding.badgeRewardGps.text = "UNLOCKED ✓"
                binding.badgeRewardGps.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
            } else {
                binding.badgeRewardGps.text = "PRO / 🎬 AD"
                binding.badgeRewardGps.setTextColor(ContextCompat.getColor(requireContext(), R.color.pro_gold))
            }

            if (isDuplicatesUnlockedViaAd) {
                binding.badgeRewardDuplicates.text = "UNLOCKED ✓"
                binding.badgeRewardDuplicates.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
            } else {
                binding.badgeRewardDuplicates.text = "PRO / 🎬 AD"
                binding.badgeRewardDuplicates.setTextColor(ContextCompat.getColor(requireContext(), R.color.pro_gold))
            }
        }
        scanResult?.stats?.let { updateHeroStats(it) }
    }

    private fun showFeatureUnlockDialog(
        title: String,
        message: String,
        onUnlockViaAd: () -> Unit,
        onCancel: () -> Unit
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setIcon(R.drawable.ic_auto_fix)
            .setPositiveButton("🎬 Watch Ad to Unlock") { _, _ ->
                onUnlockViaAd()
            }
            .setNeutralButton("💎 Upgrade to Pro") { _, _ ->
                onCancel()
                ProUpgradeBottomSheet.newInstance().show(parentFragmentManager, ProUpgradeBottomSheet.TAG)
            }
            .setNegativeButton("Keep Off") { _, _ ->
                onCancel()
            }
            .setOnCancelListener {
                onCancel()
            }
            .show()
    }

    private fun hasMismatch(s: ScanStats): Boolean {
        val totalFoundWithDuplicates = s.totalMediaFiles + s.duplicateCount
        return !s.hasMetadataFile ||
                s.totalMediaFiles > s.matchedCount ||
                s.totalMetadataEntries > totalFoundWithDuplicates
    }

    private fun updateProgressUI(progress: ScanProgress) {
        if (!progress.isComplete && scanResult == null) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                if (_binding != null && scanResult == null) {
                    val numberFormat = NumberFormat.getIntegerInstance()
                    binding.tvLiveScanCounter.text = "${numberFormat.format(progress.totalFound)} memories discovered"
                    if (progress.isMatchingPhase) {
                        binding.tvFolderScanStatus.text = "Matching timeline history..."
                    } else {
                        binding.tvFolderScanStatus.text = "Reading original timestamps from archive..."
                    }
                }
            }
        }
    }

    private fun updateHeroStats(s: ScanStats) {
        if (_binding == null) return
        val numberFormat = NumberFormat.getIntegerInstance()
        val isDeduplicating = binding.switchSkipDuplicates.isChecked

        val total = if (isDeduplicating) {
            s.totalMediaFiles
        } else {
            if (s.rawMediaCount > 0) s.rawMediaCount else (s.totalMediaFiles + s.duplicateCount)
        }

        val photos = if (isDeduplicating) {
            s.photoCount
        } else {
            if (s.rawPhotoCount > 0) s.rawPhotoCount else s.photoCount
        }

        val videos = if (isDeduplicating) {
            s.videoCount
        } else {
            if (s.rawVideoCount > 0) s.rawVideoCount else s.videoCount
        }

        binding.tvTotalFiles.text = numberFormat.format(total)
        binding.tvPhotoCount.text = "${numberFormat.format(photos)} Photos"
        binding.tvVideoCount.text = "${numberFormat.format(videos)} Videos"

        if (isDeduplicating && s.duplicateCount > 0) {
            val totalScanned = if (s.rawMediaCount > 0) s.rawMediaCount else s.totalMediaFiles + s.duplicateCount
            binding.tvTotalSubtitle.text = "${numberFormat.format(s.duplicateCount)} duplicates excluded (from ${numberFormat.format(totalScanned)} total)"
            binding.tvTotalSubtitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
        } else if (s.duplicateCount > 0) {
            binding.tvTotalSubtitle.text = "Includes ${numberFormat.format(s.duplicateCount)} duplicate throwback copies"
            binding.tvTotalSubtitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        } else {
            binding.tvTotalSubtitle.text = "All memories unique"
            binding.tvTotalSubtitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        }

        if (total > 0) {
            val photoPct = (photos * 100) / total
            val videoPct = 100 - photoPct
            binding.tvPhotoPercentage.text = "($photoPct%)"
            binding.tvVideoPercentage.text = "($videoPct%)"

            binding.layoutDistributionBar.visibility = View.VISIBLE
            val photoWeight = maxOf(photos.toFloat(), 0.01f)
            val videoWeight = maxOf(videos.toFloat(), 0.01f)
            binding.viewPhotoRatio.layoutParams =
                (binding.viewPhotoRatio.layoutParams as LinearLayout.LayoutParams).apply {
                    weight = photoWeight
                }
            binding.viewVideoRatio.layoutParams =
                (binding.viewVideoRatio.layoutParams as LinearLayout.LayoutParams).apply {
                    weight = videoWeight
                }
        } else {
            binding.layoutDistributionBar.visibility = View.GONE
        }
    }

    private fun populateStats(result: FullScanResult) {
        val s = result.stats
        val numberFormat = NumberFormat.getIntegerInstance()

        // Phase Transition: Smooth crossfade from Scanning Phase to Ready Phase
        binding.layoutScanningPhase.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction {
                if (_binding != null) {
                    binding.layoutScanningPhase.visibility = View.GONE
                    binding.layoutReadyPhase.apply {
                        alpha = 0f
                        translationY = 30f
                        visibility = View.VISIBLE
                        animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(300)
                            .start()
                    }
                }
            }.start()

        updateHeroStats(s)

        val isPro = (activity?.application as? MemoryFixApp)?.billingManager?.isPro?.value ?: false
        updateProUi(isPro)

        // Automatic Highlights & Group Visibility
        if (s.overlayCount > 0) {
            binding.layoutHighlightOverlays.visibility = View.VISIBLE
            binding.tvHighlightOverlays.text = "${numberFormat.format(s.overlayCount)} stickers & captions will be combined onto photos"
            binding.groupStickersAndOverlays.visibility = View.VISIBLE
            binding.cardOverlays.visibility = View.VISIBLE
            binding.tvOverlayTitle.text = "Combine Photo Overlays (${numberFormat.format(s.photoOverlayCount)})"
            if (s.videoOverlayCount > 0) {
                binding.dividerVideoOverlay.visibility = View.VISIBLE
                binding.layoutVideoOverlayOption.visibility = View.VISIBLE
                binding.tvVideoOverlayDesc.text = "Re-encodes ${s.videoOverlayCount} videos to bake stickers into frames (~15s/video)"
            } else {
                binding.dividerVideoOverlay.visibility = View.GONE
                binding.layoutVideoOverlayOption.visibility = View.GONE
            }
        } else {
            binding.layoutHighlightOverlays.visibility = View.GONE
            binding.groupStickersAndOverlays.visibility = View.GONE
        }

        if (s.videoChunkGroupsCount > 0) {
            binding.layoutHighlightVideoChunks.visibility = View.VISIBLE
            binding.tvHighlightVideoChunks.text = "${s.videoChunkGroupsCount} multi-part recordings will be stitched in order"
            binding.groupVideoChunks.visibility = View.VISIBLE
            binding.layoutMergeVideoChunksOption.visibility = View.VISIBLE
            binding.tvMergeVideoChunksTitle.text = "Stitch ${s.videoChunkGroupsCount} Multi-Part Videos"
        } else {
            binding.layoutHighlightVideoChunks.visibility = View.GONE
            binding.groupVideoChunks.visibility = View.GONE
        }

        if (s.duplicateCount > 0) {
            binding.dividerDuplicates.visibility = View.VISIBLE
            binding.layoutSkipDuplicatesOption.visibility = View.VISIBLE
            binding.tvSkipDuplicatesDesc.text = "${numberFormat.format(s.duplicateCount)} duplicate memories found — tap to clean and keep only 1 copy"
        } else {
            binding.dividerDuplicates.visibility = View.GONE
            binding.layoutSkipDuplicatesOption.visibility = View.GONE
        }

        // Mismatch Notification
        if (hasMismatch(s)) {
            binding.cardMismatch.visibility = View.VISIBLE
            if (!s.hasMetadataFile) {
                binding.tvMismatchTitle.text = "Missing memories_history.json"
                binding.tvMismatchDesc.text = "Archive is missing the metadata index. Timestamps will fall back to media timestamps where available."
            } else if (s.totalMetadataEntries > (s.totalMediaFiles + s.duplicateCount)) {
                val missing = s.totalMetadataEntries - (s.totalMediaFiles + s.duplicateCount)
                binding.tvMismatchTitle.text = "$missing Memories Missing from Export"
                binding.tvMismatchDesc.text = "Snapchat metadata references ${s.totalMetadataEntries} memories, but only ${s.totalMediaFiles + s.duplicateCount} were found in the selected files."
            } else {
                val unmatched = s.totalMediaFiles - s.matchedCount
                binding.tvMismatchTitle.text = "$unmatched Files Without Metadata"
                binding.tvMismatchDesc.text = "Some media files could not be matched with memories history. Their existing timestamps will be preserved."
            }
        } else {
            binding.cardMismatch.visibility = View.GONE
        }

        binding.btnStartProcessing.isEnabled = true
        binding.btnStartProcessing.alpha = 1.0f
    }

    private fun showMismatchWarningDialog(s: ScanStats) {
        val message = when {
            !s.hasMetadataFile ->
                "The archive doesn't contain memories_history.json. We will restore dates using media timestamps where available."
            s.totalMetadataEntries > (s.totalMediaFiles + s.duplicateCount) -> {
                val missing = s.totalMetadataEntries - (s.totalMediaFiles + s.duplicateCount)
                "Snapchat metadata lists ${s.totalMetadataEntries} items, but only ${s.totalMediaFiles + s.duplicateCount} files exist. $missing memories appear to be missing from the export.\n\nWe will restore all ${s.totalMediaFiles} available files."
            }
            else ->
                "Some files could not be matched with your Snapchat timeline. Their internal dates will be kept as-is."
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Proceed with Fix?")
            .setMessage(message)
            .setPositiveButton("Start Fixing Anyway") { _, _ ->
                startProcessing()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTimezoneDialog() {
        val popularZones = listOf(
            "UTC",
            "America/New_York",
            "America/Chicago",
            "America/Denver",
            "America/Los_Angeles",
            "Europe/London",
            "Europe/Paris",
            "Europe/Berlin",
            "Asia/Dubai",
            "Asia/Kolkata",
            "Asia/Singapore",
            "Asia/Tokyo",
            "Australia/Sydney",
            TimeZone.getDefault().id
        ).distinct()

        val allZones = popularZones.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Select Timezone")
            .setItems(allZones) { _, which ->
                selectedTimezone = allZones[which]
                binding.tvCurrentTimezone.text = selectedTimezone
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startProcessing() {
        checkNotificationPermissionAndProceed {
            val isZipSource = !zipUris.isNullOrEmpty()
            if (folderUri == null && !isZipSource) return@checkNotificationPermissionAndProceed

            val app = activity?.application as? MemoryFixApp
            val isPro = app?.billingManager?.isPro?.value ?: false
            val stats = scanResult?.stats
            val totalMedia = stats?.totalMediaFiles ?: 0

            if (totalMedia > FREE_TIER_LIMIT && !isPro) {
                val formattedPrice = app?.billingManager?.getFormattedPrice() ?: "$4.99"
                val numberFormat = NumberFormat.getIntegerInstance()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Restore 1,500 Free or Unlock All?")
                    .setMessage("Your export has ${numberFormat.format(totalMedia)} memories.\n\nFree tier restores up to 1,500 memories per run. Upgrade to Lifetime Pro ($formattedPrice) to restore all ${numberFormat.format(totalMedia)} memories in one run with zero ads.")
                    .setIcon(R.drawable.ic_auto_fix)
                    .setPositiveButton("💎 Unlock Pro ($formattedPrice)") { _, _ ->
                        ProUpgradeBottomSheet.newInstance().show(parentFragmentManager, ProUpgradeBottomSheet.TAG)
                    }
                    .setNegativeButton("Restore 1,500 Free") { _, _ ->
                        executeProcessing(isZipSource = isZipSource, isPro = false, maxLimit = FREE_TIER_LIMIT)
                    }
                    .show()
                return@checkNotificationPermissionAndProceed
            }

            executeProcessing(isZipSource = isZipSource, isPro = isPro, maxLimit = if (isPro) -1 else FREE_TIER_LIMIT)
        }
    }

    private fun executeProcessing(isZipSource: Boolean, isPro: Boolean, maxLimit: Int) {
        val embedLocation = binding.switchEmbedLocation.isChecked && (isPro || isGpsUnlockedViaAd)
        val mergeOverlays = binding.switchOverlay.isChecked
        val burnVideoOverlays = binding.switchVideoOverlay.isChecked
        val mergeVideoChunks = binding.switchMergeVideoChunks.isChecked
        val skipDuplicates = binding.switchSkipDuplicates.isChecked && (isPro || isDuplicatesUnlockedViaAd)

        val inputDataBuilder = Data.Builder()
            .putBoolean(ExportProcessWorker.KEY_EMBED_LOCATION, embedLocation)
            .putBoolean(ExportProcessWorker.KEY_MERGE_OVERLAYS, mergeOverlays)
            .putBoolean(ExportProcessWorker.KEY_BURN_VIDEO_OVERLAYS, burnVideoOverlays)
            .putBoolean(ExportProcessWorker.KEY_MERGE_VIDEO_CHUNKS, mergeVideoChunks)
            .putBoolean(ExportProcessWorker.KEY_SKIP_DUPLICATES, skipDuplicates)
            .putString(ExportProcessWorker.KEY_TIMEZONE, selectedTimezone)
            .putInt(ExportProcessWorker.KEY_MAX_MEDIA_LIMIT, maxLimit)

        if (isZipSource) {
            inputDataBuilder.putStringArray(
                ExportProcessWorker.KEY_ZIP_URIS,
                zipUris!!.map { it.toString() }.toTypedArray()
            )
        } else {
            inputDataBuilder.putString(ExportProcessWorker.KEY_TREE_URI, folderUri.toString())
        }

        val workRequest = OneTimeWorkRequestBuilder<ExportProcessWorker>()
            .setInputData(inputDataBuilder.build())
            .build()

        AdManager.showInterstitial(requireActivity(), isPro) {
            WorkManager.getInstance(requireContext()).enqueueUniqueWork(
                "MemoryFixExport",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            (activity as? MainActivity)?.navigateToProcessing()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
