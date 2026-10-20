package com.apadev.memoryfix.ui.result

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.apadev.memoryfix.MainActivity
import com.apadev.memoryfix.R
import com.apadev.memoryfix.databinding.FragmentResultBinding
import com.google.android.play.core.review.ReviewManagerFactory

class ResultFragment : Fragment() {

    private var _binding: FragmentResultBinding? = null
    private val binding get() = _binding!!

    private var totalFixed: Int = 0
    private var photosFixed: Int = 0
    private var videosFixed: Int = 0
    private var overlaysMerged: Int = 0
    private var failedCount: Int = 0
    private var totalScanned: Int = 0

    companion object {
        private const val ARG_TOTAL_FIXED = "ARG_TOTAL_FIXED"
        private const val ARG_PHOTOS_FIXED = "ARG_PHOTOS_FIXED"
        private const val ARG_VIDEOS_FIXED = "ARG_VIDEOS_FIXED"
        private const val ARG_OVERLAYS_MERGED = "ARG_OVERLAYS_MERGED"
        private const val ARG_FAILED_COUNT = "ARG_FAILED_COUNT"
        private const val ARG_TOTAL_SCANNED = "ARG_TOTAL_SCANNED"

        fun newInstance(
            totalFixed: Int,
            photosFixed: Int,
            videosFixed: Int,
            overlaysMerged: Int = 0,
            failedCount: Int = 0,
            totalScanned: Int = 0
        ): ResultFragment {
            return ResultFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TOTAL_FIXED, totalFixed)
                    putInt(ARG_PHOTOS_FIXED, photosFixed)
                    putInt(ARG_VIDEOS_FIXED, videosFixed)
                    putInt(ARG_OVERLAYS_MERGED, overlaysMerged)
                    putInt(ARG_FAILED_COUNT, failedCount)
                    putInt(ARG_TOTAL_SCANNED, totalScanned)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        totalFixed = arguments?.getInt(ARG_TOTAL_FIXED, 0) ?: 0
        photosFixed = arguments?.getInt(ARG_PHOTOS_FIXED, 0) ?: 0
        videosFixed = arguments?.getInt(ARG_VIDEOS_FIXED, 0) ?: 0
        overlaysMerged = arguments?.getInt(ARG_OVERLAYS_MERGED, 0) ?: 0
        failedCount = arguments?.getInt(ARG_FAILED_COUNT, 0) ?: 0
        totalScanned = arguments?.getInt(ARG_TOTAL_SCANNED, 0) ?: 0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Prominent Dual Cards: Successful vs Failed
        binding.tvHeaderSuccessCount.text = totalFixed.toString()
        binding.tvHeaderFailedCount.text = failedCount.toString()

        if (failedCount > 0) {
            val errorColor = ContextCompat.getColor(requireContext(), R.color.error)
            binding.ivFailedIcon.setColorFilter(errorColor)
            binding.tvFailedLabel.setTextColor(errorColor)
            binding.tvHeaderFailedCount.setTextColor(errorColor)

            binding.layoutSkippedRow.visibility = View.VISIBLE
            binding.layoutSkippedContent.visibility = View.VISIBLE
            binding.tvResultSkippedCount.text = failedCount.toString()
        } else {
            val secondaryColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
            binding.ivFailedIcon.setColorFilter(secondaryColor)
            binding.tvFailedLabel.setTextColor(secondaryColor)
            binding.tvHeaderFailedCount.setTextColor(secondaryColor)

            binding.layoutSkippedRow.visibility = View.GONE
            binding.layoutSkippedContent.visibility = View.GONE
        }

        // Show actual fixed count vs total scanned in summary card
        binding.tvResultDatesCount.text = if (totalScanned > 0 && totalScanned != totalFixed) {
            "$totalFixed of $totalScanned"
        } else {
            totalFixed.toString()
        }

        binding.tvResultPhotosCount.text = photosFixed.toString()
        binding.tvResultVideosCount.text = videosFixed.toString()
        binding.tvResultOverlaysCount.text = overlaysMerged.toString()

        binding.btnOpenGallery.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_GALLERY)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    startActivity(fallbackIntent)
                } catch (ex: Exception) {
                    // Ignore if no gallery app responds
                }
            }
        }

        binding.btnFixAnother.setOnClickListener {
            (activity as? MainActivity)?.navigateToHome()
        }

        // Request in-app review
        try {
            val manager = ReviewManagerFactory.create(requireContext())
            val request = manager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val reviewInfo = task.result
                    activity?.let { act ->
                        manager.launchReviewFlow(act, reviewInfo)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
