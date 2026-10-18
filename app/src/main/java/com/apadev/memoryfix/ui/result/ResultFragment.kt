package com.apadev.memoryfix.ui.result

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.apadev.memoryfix.MainActivity
import com.apadev.memoryfix.databinding.FragmentResultBinding
import com.google.android.play.core.review.ReviewManagerFactory

class ResultFragment : Fragment() {

    private var _binding: FragmentResultBinding? = null
    private val binding get() = _binding!!

    private var totalCount: Int = 0

    companion object {
        private const val ARG_TOTAL_COUNT = "ARG_TOTAL_COUNT"

        fun newInstance(total: Int): ResultFragment {
            return ResultFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TOTAL_COUNT, total)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        totalCount = arguments?.getInt(ARG_TOTAL_COUNT, 0) ?: 0
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

        binding.tvResultDatesCount.text = totalCount.toString()
        binding.tvResultOverlaysCount.text = "All matched"

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
