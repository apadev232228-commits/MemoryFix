package com.apadev.memoryfix.ui.processing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
                val fileName = workInfo.progress.getString(ExportProcessWorker.PROGRESS_FILE) ?: "Processing..."

                if (total > 0) {
                    val percent = (current * 100) / total
                    binding.tvProgressPercentage.text = "$percent%"
                    binding.tvProgressCount.text = "$current of $total memories fixed"
                    binding.progressLinear.isIndeterminate = false
                    binding.progressLinear.max = total
                    binding.progressLinear.progress = current
                } else {
                    binding.progressLinear.isIndeterminate = true
                }

                binding.tvCurrentFileName.text = fileName

                if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                    (activity as? MainActivity)?.navigateToResult(current)
                } else if (workInfo.state == WorkInfo.State.FAILED) {
                    Toast.makeText(requireContext(), "Processing encountered an issue", Toast.LENGTH_LONG).show()
                    (activity as? MainActivity)?.navigateToHome()
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
