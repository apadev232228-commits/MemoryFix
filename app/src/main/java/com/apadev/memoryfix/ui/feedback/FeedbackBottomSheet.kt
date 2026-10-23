package com.apadev.memoryfix.ui.feedback

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.apadev.memoryfix.databinding.SheetFeedbackBinding
import com.apadev.memoryfix.logging.AppLogger
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class FeedbackBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetFeedbackBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val TAG = "FeedbackBottomSheet"
        private const val SUPPORT_EMAIL = "apadev232228@gmail.com"

        fun newInstance(prefilledErrorContext: String? = null): FeedbackBottomSheet {
            val sheet = FeedbackBottomSheet()
            if (!prefilledErrorContext.isNullOrBlank()) {
                sheet.arguments = Bundle().apply {
                    putString("ARG_PREFILLED_CONTEXT", prefilledErrorContext)
                }
            }
            return sheet
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetFeedbackBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefilledContext = arguments?.getString("ARG_PREFILLED_CONTEXT")
        if (!prefilledContext.isNullOrBlank()) {
            binding.etFeedbackMessage.setText(prefilledContext)
            binding.chipBug.isChecked = true
        }

        binding.tvAppVersion.text = "MemoryFix v${com.apadev.memoryfix.BuildConfig.VERSION_NAME}"
        binding.btnCheckUpdate.setOnClickListener {
            (activity as? com.apadev.memoryfix.MainActivity)?.checkForAppUpdate(isUserInitiated = true)
        }

        binding.btnCancelFeedback.setOnClickListener {
            dismiss()
        }

        binding.btnViewLogs.setOnClickListener {
            showLogPreviewDialog()
        }

        binding.btnSendFeedback.setOnClickListener {
            sendFeedbackEmail()
        }
    }

    private fun showLogPreviewDialog() {
        val recentLogs = AppLogger.getRecentLogs(requireContext(), maxLines = 120)
        val textView = TextView(requireContext()).apply {
            text = recentLogs
            textSize = 11f
            setPadding(40, 24, 40, 24)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scrollView = ScrollView(requireContext()).apply {
            addView(textView)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Diagnostic Log Preview")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun sendFeedbackEmail() {
        val userMessage = binding.etFeedbackMessage.text?.toString()?.trim() ?: ""
        if (userMessage.isEmpty()) {
            binding.tilFeedback.error = "Please describe the issue or feedback"
            return
        }
        binding.tilFeedback.error = null

        val category = when {
            binding.chipIdea.isChecked -> "Feature Idea"
            binding.chipQuestion.isChecked -> "Question"
            else -> "Bug / Issue"
        }

        val subject = "[MemoryFix Feedback] $category - ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        val includeLogs = binding.switchIncludeLogs.isChecked

        val bodyBuilder = StringBuilder()
        bodyBuilder.append("User Description:\n")
        bodyBuilder.append(userMessage).append("\n\n")
        bodyBuilder.append("========================================\n")
        bodyBuilder.append("Device Telemetry:\n")
        bodyBuilder.append(AppLogger.getSystemReport(requireContext())).append("\n")
        bodyBuilder.append("========================================\n")

        val latestManifest = AppLogger.getLatestManifest(requireContext())
        if (!latestManifest.isNullOrBlank()) {
            bodyBuilder.append("\n").append(latestManifest).append("\n")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, bodyBuilder.toString())

            if (includeLogs) {
                try {
                    val zipLogFile = AppLogger.getExportableLogZip(requireContext())
                    if (zipLogFile.exists() && zipLogFile.length() > 0) {
                        val fileUri = FileProvider.getUriForFile(
                            requireContext(),
                            "${requireContext().packageName}.fileprovider",
                            zipLogFile
                        )
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } catch (e: Exception) {
                    AppLogger.e("FeedbackBottomSheet", "Failed to attach log zip: ${e.message}", e)
                }
            }
        }

        try {
            startActivity(Intent.createChooser(intent, "Send feedback via..."))
            Toast.makeText(requireContext(), "Opening email client...", Toast.LENGTH_SHORT).show()
            dismiss()
        } catch (e: Exception) {
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("MemoryFix Diagnostic Report", bodyBuilder.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "No email app found. Diagnostic summary copied to clipboard.", Toast.LENGTH_LONG).show()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
