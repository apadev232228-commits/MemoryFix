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
    private var scanResult: FullScanResult? = null
    private var selectedTimezone: String = TimeZone.getDefault().id

    companion object {
        private const val ARG_FOLDER_URI = "ARG_FOLDER_URI"

        fun newInstance(uri: Uri): AnalysisFragment {
            return AnalysisFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_FOLDER_URI, uri)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable(ARG_FOLDER_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelable(ARG_FOLDER_URI)
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
        val uri = folderUri ?: return

        binding.tvCurrentTimezone.text = selectedTimezone
        binding.tvFolderScanStatus.text = "Scanning export folder..."
        binding.progressScan.visibility = View.VISIBLE
        binding.ivScanComplete.visibility = View.GONE
        binding.btnStartProcessing.isEnabled = false
        binding.btnStartProcessing.alpha = 0.5f

        // Start scanning with real-time live progress updates
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                MediaScanner.scanTree(requireContext(), uri, selectedTimezone) { progress ->
                    if (!progress.isComplete && scanResult == null) {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            if (_binding != null && scanResult == null) {
                                val numberFormat = NumberFormat.getIntegerInstance()
                                binding.tvTotalFiles.text = numberFormat.format(progress.totalFound)
                                binding.tvPhotoCount.text = "${numberFormat.format(progress.photosFound)} Photos"
                                binding.tvVideoCount.text = "${numberFormat.format(progress.videosFound)} Videos"

                                if (progress.totalFound > 0) {
                                    val photoPct = (progress.photosFound * 100) / progress.totalFound
                                    val videoPct = 100 - photoPct
                                    binding.tvPhotoPercentage.text = "$photoPct%"
                                    binding.tvVideoPercentage.text = "$videoPct%"

                                    binding.layoutDistributionBar.visibility = View.VISIBLE
                                    val photoWeight = maxOf(progress.photosFound.toFloat(), 0.01f)
                                    val videoWeight = maxOf(progress.videosFound.toFloat(), 0.01f)
                                    binding.viewPhotoRatio.layoutParams =
                                        (binding.viewPhotoRatio.layoutParams as LinearLayout.LayoutParams).apply {
                                            weight = photoWeight
                                        }
                                    binding.viewVideoRatio.layoutParams =
                                        (binding.viewVideoRatio.layoutParams as LinearLayout.LayoutParams).apply {
                                            weight = videoWeight
                                        }
                                }

                                if (progress.overlaysFound > 0) {
                                    binding.cardOverlays.visibility = View.VISIBLE
                                    binding.tvOverlayTitle.text = "${numberFormat.format(progress.overlaysFound)} Stickers & Captions"
                                }

                                if (progress.duplicatesFound > 0) {
                                    binding.cardDuplicates.visibility = View.VISIBLE
                                    binding.tvDuplicateTitle.text = "${numberFormat.format(progress.duplicatesFound)} Duplicate Files Found"
                                }

                                if (progress.isMatchingPhase) {
                                    binding.tvFolderScanStatus.text = "Preparing memories: ${progress.matchedCount} of ${progress.totalFound}..."
                                } else {
                                    binding.tvFolderScanStatus.text = "Scanning: ${numberFormat.format(progress.totalFound)} items found..."
                                }
                            }
                        }
                    }
                }
            }
            scanResult = result
            populateStats(result)
        }

        binding.btnChangeTimezone.setOnClickListener {
            showTimezoneDialog()
        }

        binding.switchVideoOverlay.setOnClickListener {
            if (binding.switchVideoOverlay.isChecked) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Burn Stickers into Videos?")
                    .setMessage("Baking stickers into videos requires frame-by-frame re-encoding:\n\n• Processing takes much longer (~10–20 seconds per video).\n• Uses significantly more battery and device storage.\n• Video compression quality may be slightly reduced compared to the original untouched videos.\n\nIf turned off, video stickers are still saved with matching capture dates alongside your videos.")
                    .setPositiveButton("Enable (Slower)") { _, _ ->
                        // Keep enabled
                    }
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
    }

    private fun hasMismatch(s: ScanStats): Boolean {
        return !s.hasMetadataFile ||
                s.totalMediaFiles != s.matchedCount ||
                s.totalMetadataEntries > s.totalMediaFiles
    }

    private fun populateStats(result: FullScanResult) {
        val s = result.stats
        val numberFormat = NumberFormat.getIntegerInstance()

        binding.progressScan.visibility = View.GONE
        binding.ivScanComplete.visibility = View.VISIBLE
        binding.tvFolderScanStatus.text = "Scan complete • ${numberFormat.format(s.totalMediaFiles)} memories ready"
        binding.tvTotalFiles.text = numberFormat.format(s.totalMediaFiles)
        binding.tvPhotoCount.text = "${numberFormat.format(s.photoCount)} Photos"
        binding.tvVideoCount.text = "${numberFormat.format(s.videoCount)} Videos"

        if (s.totalMediaFiles > 0) {
            val photoPct = (s.photoCount * 100) / s.totalMediaFiles
            val videoPct = 100 - photoPct
            binding.tvPhotoPercentage.text = "$photoPct%"
            binding.tvVideoPercentage.text = "$videoPct%"

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

        // Overlays / Stickers Card
        if (s.overlayCount > 0) {
            binding.cardOverlays.visibility = View.VISIBLE
            binding.tvOverlayTitle.text = "${numberFormat.format(s.overlayCount)} Stickers & Captions"
            if (s.videoOverlayCount > 0) {
                binding.tvOverlaySubtitle.text = "${numberFormat.format(s.photoOverlayCount)} on photos (merged) • ${numberFormat.format(s.videoOverlayCount)} on videos"
                binding.layoutVideoOverlayOption.visibility = View.VISIBLE
                binding.tvVideoOverlayDesc.text = "Re-encodes ${s.videoOverlayCount} videos to bake stickers into frames (~15s per video)."
            } else {
                binding.tvOverlaySubtitle.text = "Will be combined onto matching photos"
                binding.layoutVideoOverlayOption.visibility = View.GONE
            }
            binding.switchOverlay.isEnabled = true
            binding.switchOverlay.isChecked = true
        } else {
            binding.cardOverlays.visibility = View.GONE
            binding.layoutVideoOverlayOption.visibility = View.GONE
            binding.switchOverlay.isEnabled = false
            binding.switchOverlay.isChecked = false
        }

        // Duplicates Card
        if (s.duplicateCount > 0) {
            binding.cardDuplicates.visibility = View.VISIBLE
            binding.tvDuplicateTitle.text = "${numberFormat.format(s.duplicateCount)} Duplicate Files Found"
        } else {
            binding.cardDuplicates.visibility = View.GONE
        }

        // Multi-Part Video Chunks Toggle: Shown ONLY if consecutive video chunks were detected
        if (s.videoChunkGroupsCount > 0) {
            binding.layoutMergeVideoChunksOption.visibility = View.VISIBLE
            binding.tvMergeVideoChunksTitle.text = "Merge ${s.videoChunkGroupsCount} Multi-Part Videos"
            binding.tvMergeVideoChunksDesc.text = "Stitches ${s.videoChunkFilesCount} consecutive 10s recording clips into full continuous videos."
            binding.switchMergeVideoChunks.isChecked = true
        } else {
            binding.layoutMergeVideoChunksOption.visibility = View.GONE
            binding.switchMergeVideoChunks.isChecked = false
        }

        // Mismatch Card: only visible if there is an actual count mismatch or missing metadata file
        if (hasMismatch(s)) {
            binding.cardMismatch.visibility = View.VISIBLE
            if (!s.hasMetadataFile) {
                binding.tvMismatchTitle.text = "Export History Not Found"
                binding.tvMismatchDesc.text = "Could not find a Snapchat history file in this folder. We will restore dates using original file creation timestamps."
            } else if (s.totalMetadataEntries > s.totalMediaFiles) {
                val missingCount = s.totalMetadataEntries - s.totalMediaFiles
                binding.tvMismatchTitle.text = "Missing Memories ($missingCount files)"
                binding.tvMismatchDesc.text = "Your export history lists ${numberFormat.format(s.totalMetadataEntries)} memories, but only ${numberFormat.format(s.totalMediaFiles)} files were found in this folder (${numberFormat.format(missingCount)} missing). If your export had multiple ZIP parts, make sure all parts are extracted into this folder."
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
            binding.btnStartProcessing.text = "Start Fixing Memories (~${s.estimatedTimeSeconds}s)"
        } else {
            binding.btnStartProcessing.isEnabled = false
            binding.btnStartProcessing.alpha = 0.5f
            binding.tvFolderScanStatus.text = "No media files found in this folder"
        }
    }

    private fun showMismatchWarningDialog(stats: ScanStats) {
        val numberFormat = NumberFormat.getIntegerInstance()
        val title: String
        val message: String
        val positiveButtonText: String

        if (!stats.hasMetadataFile) {
            title = "Export History Not Found"
            message = "We couldn't find your Snapchat history metadata file in this folder.\n\nCapture dates will be restored using each file's original creation timestamp instead.\n\nWould you like to proceed?"
            positiveButtonText = "Fix Using Timestamps"
        } else if (stats.totalMetadataEntries > stats.totalMediaFiles) {
            val missingCount = stats.totalMetadataEntries - stats.totalMediaFiles
            title = "Missing Memories Detected"
            message = "Your Snapchat history lists ${numberFormat.format(stats.totalMetadataEntries)} memories, but only ${numberFormat.format(stats.totalMediaFiles)} files were found in this folder (${numberFormat.format(missingCount)} missing).\n\nIf your export came in multiple ZIP files (e.g. part 1 and part 2), make sure all parts are extracted into this folder.\n\nWould you like to fix the ${numberFormat.format(stats.totalMediaFiles)} available files now, or choose another folder?"
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
            .setNegativeButton("Choose Another Folder") { _, _ ->
                (activity as? MainActivity)?.navigateToHome()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showTimezoneDialog() {
        val defaultId = TimeZone.getDefault().id
        val popularZones = listOf(
            defaultId,
            "Asia/Calcutta",
            "Asia/Kolkata",
            "UTC",
            "America/New_York",
            "America/Chicago",
            "America/Denver",
            "America/Los_Angeles",
            "Europe/London",
            "Europe/Paris",
            "Europe/Berlin",
            "Asia/Dubai",
            "Asia/Singapore",
            "Asia/Tokyo",
            "Australia/Sydney"
        ).distinct()
        val allZones = (popularZones + TimeZone.getAvailableIDs().sorted()).distinct().toTypedArray()

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
        val uri = folderUri ?: return
        val app = requireActivity().application as? MemoryFixApp
        val isPro = app?.billingManager?.isPro?.value ?: false

        AdManager.showInterstitial(requireActivity(), isPro) {
            val workData = Data.Builder()
                .putString(ExportProcessWorker.KEY_TREE_URI, uri.toString())
                .putBoolean(ExportProcessWorker.KEY_EMBED_LOCATION, binding.switchLocation.isChecked)
                .putBoolean(ExportProcessWorker.KEY_MERGE_OVERLAYS, binding.switchOverlay.isChecked)
                .putBoolean(ExportProcessWorker.KEY_BURN_VIDEO_OVERLAYS, binding.switchVideoOverlay.isChecked)
                .putBoolean(ExportProcessWorker.KEY_MERGE_VIDEO_CHUNKS, binding.switchMergeVideoChunks.isChecked)
                .putString(ExportProcessWorker.KEY_TIMEZONE, selectedTimezone)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<ExportProcessWorker>()
                .setInputData(workData)
                .build()

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
