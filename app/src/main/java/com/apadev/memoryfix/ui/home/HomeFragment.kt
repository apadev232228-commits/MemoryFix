package com.apadev.memoryfix.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.apadev.memoryfix.MainActivity
import com.apadev.memoryfix.MemoryFixApp
import com.apadev.memoryfix.R
import com.apadev.memoryfix.databinding.FragmentHomeBinding

import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import com.apadev.memoryfix.data.ZipMetadataReader
import com.apadev.memoryfix.ui.pro.ProUpgradeBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val zipMimeTypes = arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
        "*/*"
    )

    private lateinit var selectZipLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectZipLauncher = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                for (uri in uris) {
                    try {
                        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        requireContext().contentResolver.takePersistableUriPermission(uri, flags)
                    } catch (e: Exception) {
                        // Ignore permission error if already granted or restricted
                    }
                }

                // Perform fast background validation
                viewLifecycleOwner.lifecycleScope.launch {
                    val validationResults = withContext(Dispatchers.IO) {
                        uris.map { ZipMetadataReader.validateSnapchatZip(requireContext(), it) }
                    }

                    val anyValid = validationResults.any { it.isValid }
                    if (!anyValid) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Invalid Snapchat Archive")
                            .setMessage("The selected file doesn't seem to contain Snapchat memories (no memories_history.json or Snapchat photos/videos found).\n\nPlease verify you selected the official ZIP downloaded from Snapchat (typically named 'mydata~...zip').")
                            .setIcon(R.drawable.ic_warning)
                            .setPositiveButton("Select Valid ZIP") { _, _ ->
                                openZipPicker()
                            }
                            .setNegativeButton("Cancel", null)
                            .setCancelable(false)
                            .show()
                    } else {
                        (activity as? MainActivity)?.navigateToAnalysisWithZips(uris)
                    }
                }
            } else {
                Toast.makeText(requireContext(), "No ZIP file selected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openZipPicker() {
        selectZipLauncher.launch(zipMimeTypes)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnChooseZip.setOnClickListener {
            openZipPicker()
        }

        binding.cardSelectZip.setOnClickListener {
            openZipPicker()
        }

        binding.btnFeatures.setOnClickListener {
            showFeaturesBottomSheet()
        }

        binding.btnHelp.setOnClickListener {
            showHowItWorksBottomSheet()
        }

        binding.btnInfo.setOnClickListener {
            showWhyMemoryFixBottomSheet()
        }

        binding.btnPro.setOnClickListener {
            ProUpgradeBottomSheet.newInstance().show(parentFragmentManager, ProUpgradeBottomSheet.TAG)
        }

        binding.layoutHowItWorksCard.setOnClickListener {
            showHowItWorksBottomSheet()
        }

        binding.btnFeedbackSupport.setOnClickListener {
            com.apadev.memoryfix.ui.feedback.FeedbackBottomSheet.newInstance()
                .show(parentFragmentManager, com.apadev.memoryfix.ui.feedback.FeedbackBottomSheet.TAG)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val app = activity?.application as? MemoryFixApp
            app?.billingManager?.isPro?.collectLatest { isPro ->
                if (_binding != null) {
                    if (isPro) {
                        binding.tvProButton.text = "👑 PRO"
                        binding.tvProButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
                    } else {
                        binding.tvProButton.text = "👑 UPGRADE"
                        binding.tvProButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.pro_gold))
                    }
                }
            }
        }
    }

    private fun showFeaturesBottomSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_features, null)

        sheetView.findViewById<View>(R.id.btnDismissFeatures)?.setOnClickListener {
            dialog.dismiss()
        }
        sheetView.findViewById<View>(R.id.btnCloseFeaturesTop)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setContentView(sheetView)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<android.widget.FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val displayMetrics = resources.displayMetrics
                val targetHeight = (displayMetrics.heightPixels * 0.85).toInt()
                bottomSheet.layoutParams.height = targetHeight
                bottomSheet.requestLayout()

                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.peekHeight = targetHeight
            }
        }

        dialog.show()
    }

    private fun showHowItWorksBottomSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_steps, null)
        sheetView.findViewById<View>(R.id.btnDismissSteps).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setContentView(sheetView)
        dialog.show()
    }

    private fun showWhyMemoryFixBottomSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_why, null)
        sheetView.findViewById<View>(R.id.btnDismissWhy).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setContentView(sheetView)
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
