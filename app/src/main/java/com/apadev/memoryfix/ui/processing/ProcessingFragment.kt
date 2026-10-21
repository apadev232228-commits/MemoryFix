package com.apadev.memoryfix.ui.processing

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.apadev.memoryfix.MainActivity
import com.apadev.memoryfix.databinding.FragmentProcessingBinding
import com.apadev.memoryfix.worker.ExportProcessWorker

class ProcessingFragment : Fragment() {

    private var _binding: FragmentProcessingBinding? = null
    private val binding get() = _binding!!

    private var pulseAnimator: ObjectAnimator? = null
    private var sparkleAnimator: ObjectAnimator? = null

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

        startPhotoRestorationAnimation()

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
                val photosCount = workInfo.progress.getInt(ExportProcessWorker.PROGRESS_PHOTOS, 0)
                val videosCount = workInfo.progress.getInt(ExportProcessWorker.PROGRESS_VIDEOS, 0)
                val overlaysCount = workInfo.progress.getInt(ExportProcessWorker.PROGRESS_OVERLAYS, 0)

                binding.tvLivePhotosFixed.text = photosCount.toString()
                binding.tvLiveVideosFixed.text = videosCount.toString()
                binding.tvLiveOverlaysMerged.text = overlaysCount.toString()

                if (total > 0 && current > 0) {
                    val percent = (current * 100) / total
                    binding.tvProgressPercentage.text = "$percent%"
                    binding.tvSingleStatus.text = "Restoring memory $current of $total"
                    binding.tvFrameDateBadge.text = "Restoring $percent%"
                    binding.progressLinear.isIndeterminate = false
                    binding.progressLinear.max = total
                    binding.progressLinear.progress = current
                } else if (total > 0) {
                    binding.tvProgressPercentage.text = "0%"
                    binding.tvSingleStatus.text = "Preparing $total memories..."
                    binding.tvFrameDateBadge.text = "Starting..."
                    binding.progressLinear.isIndeterminate = true
                } else {
                    binding.tvProgressPercentage.text = "0%"
                    binding.tvSingleStatus.text = "Preparing your memories..."
                    binding.tvFrameDateBadge.text = "Starting..."
                    binding.progressLinear.isIndeterminate = true
                }

                if (workInfo.state == WorkInfo.State.SUCCEEDED) {
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

    private fun startPhotoRestorationAnimation() {
        val target = binding.layoutPhotoFrame
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.05f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.05f)

        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(target, scaleX, scaleY).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        sparkleAnimator = ObjectAnimator.ofFloat(binding.ivSparkleBadge, View.ROTATION, -15f, 15f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pulseAnimator?.cancel()
        pulseAnimator = null
        sparkleAnimator?.cancel()
        sparkleAnimator = null
        _binding = null
    }
}
