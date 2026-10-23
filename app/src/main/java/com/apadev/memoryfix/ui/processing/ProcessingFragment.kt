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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.apadev.memoryfix.MainActivity
import com.apadev.memoryfix.R
import com.apadev.memoryfix.analytics.AttributionManager
import com.apadev.memoryfix.databinding.FragmentProcessingBinding
import com.apadev.memoryfix.worker.ExportProcessWorker
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ProcessingFragment : Fragment() {

    private var _binding: FragmentProcessingBinding? = null
    private val binding get() = _binding!!

    private var glowAnimator: ObjectAnimator? = null
    private var iconAnimator: ObjectAnimator? = null
    private var liveDotAnimator: ObjectAnimator? = null

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

        startPulseAnimation()
        setupReferralSurvey()

        binding.btnCancelProcessing.setOnClickListener {
            showCancelConfirmationDialog()
        }

        // Intercept back gesture while processing to prevent accidental loss
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showCancelConfirmationDialog()
            }
        })

        WorkManager.getInstance(requireContext())
            .getWorkInfosForUniqueWorkLiveData("MemoryFixExport")
            .observe(viewLifecycleOwner) { workInfoList ->
                if (workInfoList.isNullOrEmpty()) return@observe
                val workInfo = workInfoList[0]

                val current = workInfo.progress.getInt(ExportProcessWorker.PROGRESS_CURRENT, 0)
                val total = workInfo.progress.getInt(ExportProcessWorker.PROGRESS_TOTAL, 0)
                val progressFile = workInfo.progress.getString(ExportProcessWorker.PROGRESS_FILE)
                val photosCount = workInfo.progress.getInt(ExportProcessWorker.PROGRESS_PHOTOS, 0)
                val videosCount = workInfo.progress.getInt(ExportProcessWorker.PROGRESS_VIDEOS, 0)
                val overlaysCount = workInfo.progress.getInt(ExportProcessWorker.PROGRESS_OVERLAYS, 0)

                binding.tvLivePhotosFixed.text = photosCount.toString()
                binding.tvLiveVideosFixed.text = videosCount.toString()
                binding.tvLiveOverlaysMerged.text = overlaysCount.toString()

                if (total > 0 && current > 0) {
                    val percent = ((current.toLong() * 100) / total).toInt().coerceIn(0, 100)
                    binding.tvProgressPercentage.text = "$percent%"
                    binding.tvItemCounter.text = "Restoring $current of $total memories"
                    val displayStatus = when {
                        progressFile?.startsWith("Stitching") == true -> progressFile
                        progressFile?.startsWith("Finalizing") == true -> progressFile
                        progressFile?.startsWith("Starting") == true -> progressFile
                        !progressFile.isNullOrEmpty() -> "Restoring: $progressFile"
                        else -> "Restoring memory $current of $total"
                    }
                    binding.tvSingleStatus.text = displayStatus
                    binding.progressLinear.isIndeterminate = false
                    binding.progressLinear.max = total
                    binding.progressLinear.progress = current
                } else if (total > 0) {
                    binding.tvProgressPercentage.text = "0%"
                    binding.tvItemCounter.text = "Preparing $total memories..."
                    binding.tvSingleStatus.text = progressFile ?: "Starting restoration..."
                    binding.progressLinear.isIndeterminate = false
                    binding.progressLinear.max = total
                    binding.progressLinear.progress = 0
                } else {
                    binding.tvProgressPercentage.text = "0%"
                    binding.tvItemCounter.text = "Initializing restoration..."
                    binding.tvSingleStatus.text = "Preparing your memories..."
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

    fun showCancelConfirmationDialog() {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_cancel_title)
            .setMessage(R.string.dialog_cancel_message)
            .setIcon(R.drawable.ic_warning)
            .setPositiveButton(R.string.dialog_keep_restoring) { d, _ ->
                d.dismiss()
            }
            .setNegativeButton(R.string.dialog_confirm_cancel) { _, _ ->
                cancelProcessing()
            }
            .show()

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.error)
        )
    }

    private fun cancelProcessing() {
        WorkManager.getInstance(requireContext()).cancelUniqueWork("MemoryFixExport")
        Toast.makeText(requireContext(), "Restoration cancelled", Toast.LENGTH_SHORT).show()
        (activity as? MainActivity)?.navigateToHome()
    }

    private fun startPulseAnimation() {
        // Soft aura breathing
        val glowTarget = binding.viewPulseGlow
        val glowScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.85f, 1.25f)
        val glowScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1.25f)
        val glowAlpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.35f, 0.85f)

        glowAnimator = ObjectAnimator.ofPropertyValuesHolder(glowTarget, glowScaleX, glowScaleY, glowAlpha).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Central icon gentle pulse
        val iconTarget = binding.ivAnimatedIcon
        val iconScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.95f, 1.08f)
        val iconScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.95f, 1.08f)
        iconAnimator = ObjectAnimator.ofPropertyValuesHolder(iconTarget, iconScaleX, iconScaleY).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Live dot subtle blink
        liveDotAnimator = ObjectAnimator.ofFloat(binding.viewLiveDot, View.ALPHA, 0.4f, 1.0f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun setupReferralSurvey() {
        if (AttributionManager.hasAnswered(requireContext())) {
            binding.cardReferralSurvey.visibility = View.GONE
            return
        }

        binding.cardReferralSurvey.visibility = View.VISIBLE

        val chipMap = mapOf(
            binding.chipReddit to "Reddit",
            binding.chipGoogle to "Google Search",
            binding.chipTikTok to "TikTok / Reels",
            binding.chipYouTube to "YouTube",
            binding.chipPlayStore to "Play Store",
            binding.chipFriend to "Friend / Family",
            binding.chipOther to "Other"
        )

        chipMap.forEach { (chip, answer) ->
            chip.setOnClickListener {
                submitSurveyAnswer(answer)
            }
        }
    }

    private fun submitSurveyAnswer(answer: String) {
        AttributionManager.submitAnswer(requireContext(), answer)

        // Disable all chips so multiple taps don't fire
        for (i in 0 until binding.chipGroupReferral.childCount) {
            binding.chipGroupReferral.getChildAt(i).isEnabled = false
        }

        // Fade out question & fade in Thank You view
        binding.layoutSurveyContent.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                if (_binding == null) return@withEndAction
                binding.layoutSurveyContent.visibility = View.GONE
                binding.layoutSurveyThankYou.alpha = 0f
                binding.layoutSurveyThankYou.visibility = View.VISIBLE
                binding.layoutSurveyThankYou.animate()
                    .alpha(1f)
                    .setDuration(250)
                    .start()

                // Smoothly collapse and hide card after 2.5 seconds
                binding.cardReferralSurvey.postDelayed({
                    if (_binding != null) {
                        binding.cardReferralSurvey.animate()
                            .alpha(0f)
                            .setDuration(400)
                            .withEndAction {
                                if (_binding != null) {
                                    binding.cardReferralSurvey.visibility = View.GONE
                                }
                            }
                            .start()
                    }
                }, 2500)
            }
            .start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        glowAnimator?.cancel()
        glowAnimator = null
        iconAnimator?.cancel()
        iconAnimator = null
        liveDotAnimator?.cancel()
        liveDotAnimator = null
        _binding = null
    }
}
