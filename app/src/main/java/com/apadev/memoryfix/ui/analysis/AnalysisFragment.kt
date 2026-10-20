package com.apadev.memoryfix.ui.analysis

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import com.apadev.memoryfix.worker.ExportProcessWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        // Start scanning with real-time live progress
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                MediaScanner.scanTree(requireContext(), uri, selectedTimezone) { progress ->
                    if (!progress.isComplete && scanResult == null) {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            if (_binding != null && scanResult == null) {
                                binding.tvTotalFiles.text = progress.totalFound.toString()
                                binding.tvPhotoCount.text = progress.photosFound.toString()
                                binding.tvVideoCount.text = progress.videosFound.toString()
                                binding.tvOverlayCount.text = progress.overlaysFound.toString()
                                binding.tvDuplicateCount.text = progress.duplicatesFound.toString()
                                binding.tvMatchedMetadata.text = progress.matchedCount.toString()

                                if (progress.isMatchingPhase) {
                                    binding.tvFolderScanStatus.text = "Matching history: ${progress.matchedCount} of ${progress.totalFound}..."
                                } else {
                                    binding.tvFolderScanStatus.text = "Scanning: ${progress.totalFound} items found..."
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

        binding.btnStartProcessing.setOnClickListener {
            startProcessing()
        }
    }

    private fun populateStats(result: FullScanResult) {
        val s = result.stats
        binding.progressScan.visibility = View.GONE
        binding.ivScanComplete.visibility = View.VISIBLE
        binding.tvFolderScanStatus.text = "Scan complete • ${s.totalMediaFiles} files ready"
        binding.tvTotalFiles.text = s.totalMediaFiles.toString()
        binding.tvPhotoCount.text = s.photoCount.toString()
        binding.tvVideoCount.text = s.videoCount.toString()
        binding.tvMatchedMetadata.text = s.matchedCount.toString()
        binding.tvOverlayCount.text = s.overlayCount.toString()
        binding.tvDuplicateCount.text = s.duplicateCount.toString()

        binding.switchOverlay.isEnabled = s.overlayCount > 0
        binding.switchOverlay.isChecked = s.overlayCount > 0

        binding.btnStartProcessing.isEnabled = true
        binding.btnStartProcessing.alpha = 1.0f
        binding.btnStartProcessing.text = "Start Fixing Memories (~${s.estimatedTimeSeconds}s)"
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
