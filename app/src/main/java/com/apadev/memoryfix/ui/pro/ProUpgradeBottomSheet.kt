package com.apadev.memoryfix.ui.pro

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.apadev.memoryfix.MemoryFixApp
import com.apadev.memoryfix.databinding.SheetProUpgradeBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProUpgradeBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetProUpgradeBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val TAG = "ProUpgradeBottomSheet"

        fun newInstance(): ProUpgradeBottomSheet {
            return ProUpgradeBottomSheet()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetProUpgradeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = activity?.application as? MemoryFixApp
        val billingManager = app?.billingManager

        val formattedPrice = billingManager?.getFormattedPrice() ?: "$4.99"
        binding.btnPurchasePro.text = "Unlock Lifetime Pro • $formattedPrice"

        binding.btnPurchasePro.setOnClickListener {
            billingManager?.launchBillingFlow(requireActivity())
        }

        binding.btnRestorePurchase.setOnClickListener {
            if (com.apadev.memoryfix.BuildConfig.DEBUG) {
                billingManager?.setProStatus(true)
                Toast.makeText(context, "Lifetime Pro Restored (Debug Mode) ✓", Toast.LENGTH_SHORT).show()
            } else {
                billingManager?.startConnection {
                    activity?.runOnUiThread {
                        Toast.makeText(context, "Purchases refreshed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            billingManager?.isPro?.collectLatest { isPro ->
                if (isPro) {
                    Toast.makeText(context, "🎉 Welcome to Lifetime Pro!", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
