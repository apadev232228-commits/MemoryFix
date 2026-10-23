package com.apadev.memoryfix.ui.analysis

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.apadev.memoryfix.MainActivity
import com.apadev.memoryfix.MemoryFixApp
import com.apadev.memoryfix.ads.AdManager
import com.apadev.memoryfix.data.FullScanResult
import com.apadev.memoryfix.data.MediaScanner
import com.apadev.memoryfix.data.ScanProgress
import com.apadev.memoryfix.databinding.FragmentAnalysisBinding
import com.apadev.memoryfix.model.ScanStats
import com.apadev.memoryfix.worker.ExportProcessWorker
import kotlinx.coroutines.Dispatchers
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
    private var isSettingsExpanded: Boolean = false

    companion object {
        private const val ARG_FOLDER_URI = "ARG_FOLDER_URI"
        private const val ARG_ZIP_URIS = "ARG_ZIP_URIS"

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

        AdManager.loadInterstitial(requireContext())

        binding.layoutScanningPhase.visibility = View.VISIBLE
        binding.layoutReadyPhase.visibility = View.GONE
        binding.tvCurrentTimezone.text = selectedTimezone
        binding.tvScanningTitle.text = if (isZipSource) "Scanning Snapchat Archive" else "Scanning Export Folder"
        binding.tvFolderScanStatus.text = "Reading timestamps & matching timeline..."
        binding.tvLiveScanCounter.text = "Initializing scan..."
        binding.btnStartProcessing.isEnabled = false
        binding.btnStartProcessing.alpha = 0.5f

        // Advanced Options Accordion Header
        binding.layoutSettingsHeader.setOnClickListener {
            isSettingsExpanded = !isSettingsExpanded
            binding.layoutSettingsContent.visibility = if (isSettingsExpanded) View.VISIBLE else View.GONE
            binding.ivSettingsChevron.animate()
                .rotation(if (isSettingsExpanded) 180f else 0f)
                .setDuration(200)
                .start()
        }

        binding.btnChangeTimezone.setOnClickListener {
            showTimezoneDialog()
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

        binding.tvTotalFiles.text = numberFormat.format(s.totalMediaFiles)
        binding.tvPhotoCount.text = "${numberFormat.format(s.photoCount)} Photos"
        binding.tvVideoCount.text = "${numberFormat.format(s.videoCount)} Videos"

        if (s.totalMediaFiles > 0) {
            val photoPct = (s.photoCount * 100) / s.totalMediaFiles
            val videoPct = 100 - photoPct
            binding.tvPhotoPercentage.text = "($photoPct%)"
            binding.tvVideoPercentage.text = "($videoPct%)"

            binding.layoutDistributionBar.visibility = View.VISIBLE
            val photoWeight = maxOf(s.photoCount.toFloat(), 0.01f)
            val videoWeight = maxOf(s.videoCount.toFloat(), 0.01f)
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

        // Automatic Highlights
        if (s.overlayCount > 0) {
            binding.layoutHighlightOverlays.visibility = View.VISIBLE
            binding.tvHighlightOverlays.text = "${numberFormat.format(s.overlayCount)} stickers & captions will be combined onto photos"
            binding.cardOverlays.visibility = View.VISIBLE
            binding.tvOverlayTitle.text = "Merge Photo Overlays (${numberFormat.format(s.photoOverlayCount)})"
            if (s.videoOverlayCount > 0) {
                binding.layoutVideoOverlayOption.visibility = View.VISIBLE
                binding.tvVideoOverlayDesc.text = "Re-encodes ${s.videoOverlayCount} videos to bake stickers into frames (~15s/video)"
            } else {
                binding.layoutVideoOverlayOption.visibility = View.GONE
            }
        } else {
            binding.layoutHighlightOverlays.visibility = View.GONE
            binding.cardOverlays.visibility = View.GONE
            binding.layoutVideoOverlayOption.visibility = View.GONE
        }

        if (s.videoChunkGroupsCount > 0) {
            binding.layoutHighlightVideoChunks.visibility = View.VISIBLE
            binding.tvHighlightVideoChunks.text = "${s.videoChunkGroupsCount} multi-part recordings will be stitched in order"
            binding.layoutMergeVideoChunksOption.visibility = View.VISIBLE
            binding.tvMergeVideoChunksTitle.text = "Stitch ${s.videoChunkGroupsCount} Multi-Part Videos"
        } else {
            binding.layoutHighlightVideoChunks.visibility = View.GONE
            binding.layoutMergeVideoChunksOption.visibility = View.GONE
        }

        if (s.duplicateCount > 0) {
            binding.layoutSkipDuplicatesOption.visibility = View.VISIBLE
            binding.tvSkipDuplicatesDesc.text = "${numberFormat.format(s.duplicateCount)} duplicate memories found — keep only 1 clean copy"
        } else {
            binding.layoutSkipDuplicatesOption.visibility = View.VISIBLE
            binding.tvSkipDuplicatesDesc.text = "Keeps only 1 copy of identical re-saved memories"
        }

        // Mismatch Alert (Visible only when needed)
        if (hasMismatch(s)) {
            binding.cardMismatch.visibility = View.VISIBLE
            if (!s.hasMetadataFile) {
                binding.tvMismatchTitle.text = "Export History Not Found"
                binding.tvMismatchDesc.text = "Could not find a Snapchat history file in this archive. We will restore dates using original file creation timestamps."
            } else if (s.totalMetadataEntries > (s.totalMediaFiles + s.duplicateCount)) {
                val missingCount = s.totalMetadataEntries - (s.totalMediaFiles + s.duplicateCount)
                binding.tvMismatchTitle.text = "Missing Memories ($missingCount files)"
                binding.tvMismatchDesc.text = "Your export history lists ${numberFormat.format(s.totalMetadataEntries)} memories, but only ${numberFormat.format(s.totalMediaFiles + s.duplicateCount)} files were found (${numberFormat.format(missingCount)} missing). If your export had multiple ZIP parts (e.g. zip-1 and zip-2), select all ZIP files together."
            } else if (s.matchedCount < s.totalMediaFiles) {
                val unmatched = s.totalMediaFiles - s.matchedCount
                binding.tvMismatchTitle.text = "Unmatched Media ($unmatched files)"
                binding.tvMismatchDesc.text = "$unmatched file(s) couldn't be matched with your export history. Their capture dates will still be fixed using file creation timestamps."
            }
        } else {
            binding.cardMismatch.visibility = View.GONE
        }

        if (s.totalMediaFiles > 0) {
            binding.btnStartProcessing.isEnabled = true
            binding.btnStartProcessing.alpha = 1.0f
            binding.btnStartProcessing.text = "Fix My Memories"
        } else {
            binding.btnStartProcessing.isEnabled = false
            binding.btnStartProcessing.alpha = 0.5f
            binding.btnStartProcessing.text = "No Memories Found"
        }
    }

    private fun showMismatchWarningDialog(stats: ScanStats) {
        val numberFormat = NumberFormat.getIntegerInstance()
        val title: String
        val message: String
        val positiveButtonText: String

        val totalFoundWithDuplicates = stats.totalMediaFiles + stats.duplicateCount
        if (!stats.hasMetadataFile) {
            title = "Export History Not Found"
            message = "We couldn't find your Snapchat history metadata file.\n\nCapture dates will be restored using each file's original creation timestamp instead.\n\nWould you like to proceed?"
            positiveButtonText = "Fix Using Timestamps"
        } else if (stats.totalMetadataEntries > totalFoundWithDuplicates) {
            val missingCount = stats.totalMetadataEntries - totalFoundWithDuplicates
            title = "Missing Memories Detected"
            message = "Your Snapchat history lists ${numberFormat.format(stats.totalMetadataEntries)} memories, but only ${numberFormat.format(totalFoundWithDuplicates)} files were found (${numberFormat.format(missingCount)} missing).\n\nIf your export came in multiple ZIP files (e.g. zip-1 and zip-2), select all ZIP parts together.\n\nWould you like to fix the ${numberFormat.format(stats.totalMediaFiles)} available files now, or select other files?"
            positiveButtonText = "Fix Available (${numberFormat.format(stats.totalMediaFiles)})"
        } else if (stats.matchedCount < stats.totalMediaFiles) {
            val unmatched = stats.totalMediaFiles - stats.matchedCount
            title = "Unmatched Memories Detected"
            message = "${numberFormat.format(unmatched)} file(s) couldn't be matched with your export history.\n\nWe will still fix their dates using file creation timestamps.\n\nWould you like to proceed?"
            positiveButtonText = "Fix All Media"
        } else {
            startProcessing()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButtonText) { _, _ ->
                startProcessing()
            }
            .setNegativeButton("Select Another Archive") { _, _ ->
                (activity as? MainActivity)?.navigateToHome()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showTimezoneDialog() {
        val defaultId = TimeZone.getDefault().id
        val popularZones = listOf(
            defaultId,
            "UTC",
            "Asia/Kolkata",
            "America/New_York",
            "America/Los_Angeles",
            "America/Chicago",
            "Europe/London",
            "Europe/Paris",
            "Asia/Dubai",
            "Asia/Tokyo",
            "Australia/Sydney"
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
        val isZipSource = !zipUris.isNullOrEmpty()
        if (folderUri == null && !isZipSource) return

        val embedLocation = true
        val mergeOverlays = binding.switchOverlay.isChecked
        val burnVideoOverlays = binding.switchVideoOverlay.isChecked
        val mergeVideoChunks = binding.switchMergeVideoChunks.isChecked
        val skipDuplicates = binding.switchSkipDuplicates.isChecked

        val inputDataBuilder = Data.Builder()
            .putBoolean(ExportProcessWorker.KEY_EMBED_LOCATION, embedLocation)
            .putBoolean(ExportProcessWorker.KEY_MERGE_OVERLAYS, mergeOverlays)
            .putBoolean(ExportProcessWorker.KEY_BURN_VIDEO_OVERLAYS, burnVideoOverlays)
            .putBoolean(ExportProcessWorker.KEY_MERGE_VIDEO_CHUNKS, mergeVideoChunks)
            .putBoolean(ExportProcessWorker.KEY_SKIP_DUPLICATES, skipDuplicates)
            .putString(ExportProcessWorker.KEY_TIMEZONE, selectedTimezone)

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

        val isPro = (activity?.application as? MemoryFixApp)?.billingManager?.isPro?.value ?: false
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
