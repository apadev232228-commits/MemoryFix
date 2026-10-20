package com.apadev.memoryfix.ui.processing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.apadev.memoryfix.MainActivity
import com.apadev.memoryfix.databinding.FragmentProcessingBinding
import com.apadev.memoryfix.worker.ExportProcessWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProcessingFragment : Fragment() {

    private var _binding: FragmentProcessingBinding? = null
    private val binding get() = _binding!!

    private var messageJob: Job? = null
    private val friendlyMessages = listOf(
        "Restoring your favorite selfies...",
        "Matching timeline dates & times...",
        "Embedding original GPS locations...",
        "Blending overlay stickers onto photos...",
        "Organizing your snap stories in order...",
        "Polishing your video memories...",
        "Aligning memories chronologically...",
        "Getting your gallery back in order..."
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProcessingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        startRotatingFriendlyMessages()

        binding.btnCancelProcessing.setOnClickListener {
            WorkManager.getInstance(requireContext()).cancelUniqueWork("MemoryFixExport")
            Toast.makeText(requireContext(), "Processing cancelled", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.navigateToHome()
        }

        WorkManager.getInstance(requireContext())
            .getWorkInfosForUniqueWorkLiveData("MemoryFixExport")
            .observe(viewLifecycleOwner) { workInfoList ->
                if (workInfoList.isNullOrEmpty()) return@observe
                val workInfo = workInfoList[0]

                val current = workInfo.progress.getInt(ExportProcessWorker.PROGRESS_CURRENT, 0)
                val total = workInfo.progress.getInt(ExportProcessWorker.PROGRESS_TOTAL, 0)
                val fileName = workInfo.progress.getString(ExportProcessWorker.PROGRESS_FILE) ?: "Preparing files..."
                val photosCount = workInfo.progress.getInt(ExportProcessWorker.PROGRESS_PHOTOS, 0)
                val videosCount = workInfo.progress.getInt(ExportProcessWorker.PROGRESS_VIDEOS, 0)
                val overlaysCount = workInfo.progress.getInt(ExportProcessWorker.PROGRESS_OVERLAYS, 0)

                binding.tvLivePhotosFixed.text = photosCount.toString()
                binding.tvLiveVideosFixed.text = videosCount.toString()
                binding.tvLiveOverlaysMerged.text = overlaysCount.toString()

                if (total > 0 && current > 0) {
                    val percent = (current * 100) / total
                    binding.tvProgressPercentage.text = "$percent%"
                    binding.tvProgressCount.text = "$current of $total memories fixed"
                    binding.progressLinear.isIndeterminate = false
                    binding.progressLinear.max = total
                    binding.progressLinear.progress = current
                } else if (total > 0) {
                    binding.tvProgressPercentage.text = "0%"
                    binding.tvProgressCount.text = "Preparing $total memories..."
                    binding.progressLinear.isIndeterminate = true
                } else {
                    binding.tvProgressPercentage.text = "0%"
                    binding.tvProgressCount.text = "Preparing your memories..."
                    binding.progressLinear.isIndeterminate = true
                }

                binding.tvCurrentFileName.text = fileName

                if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                    // Read final counts from output data
                    val resultTotal = workInfo.outputData.getInt(ExportProcessWorker.RESULT_TOTAL, current)
                    val resultPhotos = workInfo.outputData.getInt(ExportProcessWorker.RESULT_PHOTOS, photosCount)
                    val resultVideos = workInfo.outputData.getInt(ExportProcessWorker.RESULT_VIDEOS, videosCount)
                    val overlaysMerged = workInfo.outputData.getInt(ExportProcessWorker.RESULT_OVERLAYS_MERGED, overlaysCount)
                    val failedCount = workInfo.outputData.getInt(ExportProcessWorker.RESULT_FAILED, 0)
                    val totalScanned = workInfo.outputData.getInt(ExportProcessWorker.RESULT_TOTAL_SCANNED, total)

                    (activity as? MainActivity)?.navigateToResult(
                        totalFixed = resultTotal,
                        photosFixed = resultPhotos,
                        videosFixed = resultVideos,
                        overlaysMerged = overlaysMerged,
                        failedCount = failedCount,
                        totalScanned = totalScanned
                    )
                } else if (workInfo.state == WorkInfo.State.FAILED) {
                    Toast.makeText(requireContext(), "Processing encountered an issue", Toast.LENGTH_LONG).show()
                    (activity as? MainActivity)?.navigateToHome()
                }
            }
    }

    private fun startRotatingFriendlyMessages() {
        messageJob?.cancel()
        messageJob = viewLifecycleOwner.lifecycleScope.launch {
            var index = 0
            while (isActive) {
                if (_binding != null) {
                    binding.tvFriendlyStatus.apply {
                        animate().alpha(0f).setDuration(250).withEndAction {
                            if (_binding != null) {
                                text = friendlyMessages[index % friendlyMessages.size]
                                index++
                                animate().alpha(1f).setDuration(250).start()
                            }
                        }.start()
                    }
                }
                delay(2500)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        messageJob?.cancel()
        messageJob = null
        _binding = null
    }
}
