package com.apadev.memoryfix.ui.analysis

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apadev.memoryfix.R
import com.apadev.memoryfix.databinding.ItemTimezoneBinding
import com.apadev.memoryfix.databinding.SheetTimezonePickerBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TimezoneBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetTimezonePickerBinding? = null
    private val binding get() = _binding!!

    private var selectedTimezone: String = TimeZone.getDefault().id
    private var onTimezoneSelectedListener: ((String) -> Unit)? = null

    private lateinit var adapter: TimezoneAdapter
    private val allTimezones = mutableListOf<TimezoneItem>()

    companion object {
        const val TAG = "TimezoneBottomSheet"
        private const val ARG_SELECTED_TZ = "arg_selected_tz"

        fun newInstance(
            currentTimezone: String,
            onSelected: (String) -> Unit
        ): TimezoneBottomSheet {
            val sheet = TimezoneBottomSheet()
            sheet.arguments = Bundle().apply {
                putString(ARG_SELECTED_TZ, currentTimezone)
            }
            sheet.onTimezoneSelectedListener = onSelected
            return sheet
        }
    }

    data class TimezoneItem(
        val id: String,
        val displayName: String,
        val offsetFormatted: String,
        val abbreviation: String,
        val badge: String? = null,
        val searchKeywords: String = ""
    )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetTimezonePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectedTimezone = arguments?.getString(ARG_SELECTED_TZ) ?: TimeZone.getDefault().id

        buildTimezoneList()

        adapter = TimezoneAdapter(allTimezones, selectedTimezone) { chosenTz ->
            selectedTimezone = chosenTz.id
            onTimezoneSelectedListener?.invoke(chosenTz.id)
            dismiss()
        }

        binding.rvTimezones.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTimezones.adapter = adapter

        // Scroll to currently selected item if in top results
        val selectedIndex = allTimezones.indexOfFirst { it.id == selectedTimezone }
        if (selectedIndex >= 0) {
            binding.rvTimezones.scrollToPosition(selectedIndex)
        }

        binding.etSearchTimezone.doAfterTextChanged { text ->
            val query = text?.toString()?.trim() ?: ""
            binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            filterTimezones(query)
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchTimezone.setText("")
        }
    }

    private fun filterTimezones(query: String) {
        if (query.isEmpty()) {
            adapter.updateItems(allTimezones)
            binding.tvEmptySearch.visibility = View.GONE
            binding.rvTimezones.visibility = View.VISIBLE
        } else {
            val lower = query.lowercase(Locale.ROOT)
            val filtered = allTimezones.filter { item ->
                item.displayName.lowercase(Locale.ROOT).contains(lower) ||
                        item.id.lowercase(Locale.ROOT).contains(lower) ||
                        item.offsetFormatted.lowercase(Locale.ROOT).contains(lower) ||
                        item.abbreviation.lowercase(Locale.ROOT).contains(lower) ||
                        item.searchKeywords.lowercase(Locale.ROOT).contains(lower)
            }
            adapter.updateItems(filtered)
            if (filtered.isEmpty()) {
                binding.tvEmptySearch.visibility = View.VISIBLE
                binding.rvTimezones.visibility = View.GONE
            } else {
                binding.tvEmptySearch.visibility = View.GONE
                binding.rvTimezones.visibility = View.VISIBLE
            }
        }
    }

    private fun buildTimezoneList() {
        allTimezones.clear()
        val seenIds = mutableSetOf<String>()

        // 1. Device Timezone
        val deviceId = TimeZone.getDefault().id
        seenIds.add(deviceId)
        allTimezones.add(createTimezoneItem(deviceId, "Device Local Timezone", badge = "DEVICE", keywords = "local phone current"))

        // 2. UTC Standard
        if (seenIds.add("UTC")) {
            allTimezones.add(createTimezoneItem("UTC", "UTC (Coordinated Universal Time)", badge = "STANDARD", keywords = "universal gmt zero standard"))
        }

        // 3. Curated Worldwide Metropolises
        val curated = listOf(
            // North America
            CuratedTz("America/New_York", "New York, United States", "EST EDT Eastern Boston Miami Atlanta"),
            CuratedTz("America/Chicago", "Chicago, United States", "CST CDT Central Dallas Houston Austin"),
            CuratedTz("America/Denver", "Denver, United States", "MST MDT Mountain Salt Lake City"),
            CuratedTz("America/Phoenix", "Phoenix, United States", "Arizona MST"),
            CuratedTz("America/Los_Angeles", "Los Angeles, United States", "PST PDT Pacific San Francisco Seattle California"),
            CuratedTz("America/Anchorage", "Anchorage, United States", "Alaska AKST AKDT"),
            CuratedTz("Pacific/Honolulu", "Honolulu, United States", "Hawaii HST"),
            CuratedTz("America/Toronto", "Toronto, Canada", "Ontario Montreal Ottawa Eastern"),
            CuratedTz("America/Vancouver", "Vancouver, Canada", "British Columbia Pacific"),
            CuratedTz("America/Edmonton", "Edmonton / Calgary, Canada", "Alberta Mountain"),
            CuratedTz("America/Winnipeg", "Winnipeg, Canada", "Manitoba Central"),
            CuratedTz("America/Halifax", "Halifax, Canada", "Nova Scotia Atlantic AST"),
            CuratedTz("America/St_Johns", "St. John's, Canada", "Newfoundland NST"),
            CuratedTz("America/Mexico_City", "Mexico City, Mexico", "CDMX Guadalajara Monterrey"),
            CuratedTz("America/Cancun", "Cancun, Mexico", "Quintana Roo"),
            CuratedTz("America/Tijuana", "Tijuana, Mexico", "Baja California"),

            // Central & South America
            CuratedTz("America/Bogota", "Bogotá, Colombia", "Medellin Cali"),
            CuratedTz("America/Lima", "Lima, Peru", "Cusco"),
            CuratedTz("America/Buenos_Aires", "Buenos Aires, Argentina", "Cordoba Rosario"),
            CuratedTz("America/Sao_Paulo", "São Paulo, Brazil", "Rio de Janeiro Brasilia BRT"),
            CuratedTz("America/Santiago", "Santiago, Chile", "Valparaiso"),
            CuratedTz("America/Caracas", "Caracas, Venezuela", "VET"),
            CuratedTz("America/Montevideo", "Montevideo, Uruguay", "UYT"),
            CuratedTz("America/Panama", "Panama City, Panama", "EST"),
            CuratedTz("America/Costa_Rica", "San José, Costa Rica", "Central America"),

            // Europe
            CuratedTz("Europe/London", "London, United Kingdom", "UK England Britain GMT BST Manchester"),
            CuratedTz("Europe/Dublin", "Dublin, Ireland", "Irish GMT IST"),
            CuratedTz("Europe/Paris", "Paris, France", "CET CEST Lyon Marseille"),
            CuratedTz("Europe/Berlin", "Berlin, Germany", "CET CEST Frankfurt Munich Hamburg"),
            CuratedTz("Europe/Rome", "Rome, Italy", "Milan Naples CET"),
            CuratedTz("Europe/Madrid", "Madrid, Spain", "Barcelona Valencia CET"),
            CuratedTz("Europe/Amsterdam", "Amsterdam, Netherlands", "Holland Rotterdam CET"),
            CuratedTz("Europe/Brussels", "Brussels, Belgium", "Antwerp CET"),
            CuratedTz("Europe/Vienna", "Vienna, Austria", "CET"),
            CuratedTz("Europe/Zurich", "Zurich, Switzerland", "Geneva Basel Bern CET"),
            CuratedTz("Europe/Stockholm", "Stockholm, Sweden", "CET"),
            CuratedTz("Europe/Oslo", "Oslo, Norway", "CET"),
            CuratedTz("Europe/Copenhagen", "Copenhagen, Denmark", "CET"),
            CuratedTz("Europe/Helsinki", "Helsinki, Finland", "EET EEST"),
            CuratedTz("Europe/Athens", "Athens, Greece", "EET EEST"),
            CuratedTz("Europe/Warsaw", "Warsaw, Poland", "Krakow CET"),
            CuratedTz("Europe/Prague", "Prague, Czech Republic", "CET"),
            CuratedTz("Europe/Budapest", "Budapest, Hungary", "CET"),
            CuratedTz("Europe/Bucharest", "Bucharest, Romania", "EET"),
            CuratedTz("Europe/Kyiv", "Kyiv, Ukraine", "Kiev EET"),
            CuratedTz("Europe/Lisbon", "Lisbon, Portugal", "Porto WET"),
            CuratedTz("Europe/Istanbul", "Istanbul, Turkey", "Ankara TRT"),
            CuratedTz("Europe/Moscow", "Moscow, Russia", "MSK Saint Petersburg"),

            // Middle East & Africa
            CuratedTz("Asia/Dubai", "Dubai, UAE", "Abu Dhabi Gulf GST Emirates"),
            CuratedTz("Asia/Riyadh", "Riyadh, Saudi Arabia", "Jeddah Mecca AST"),
            CuratedTz("Asia/Doha", "Doha, Qatar", "AST"),
            CuratedTz("Asia/Kuwait", "Kuwait City, Kuwait", "AST"),
            CuratedTz("Asia/Jerusalem", "Jerusalem / Tel Aviv, Israel", "IST IDT"),
            CuratedTz("Asia/Beirut", "Beirut, Lebanon", "EET"),
            CuratedTz("Asia/Amman", "Amman, Jordan", "EET"),
            CuratedTz("Asia/Tehran", "Tehran, Iran", "IRST"),
            CuratedTz("Africa/Johannesburg", "Johannesburg, South Africa", "Cape Town Durban SAST"),
            CuratedTz("Africa/Cairo", "Cairo, Egypt", "Alexandria EET"),
            CuratedTz("Africa/Lagos", "Lagos, Nigeria", "Abuja WAT"),
            CuratedTz("Africa/Nairobi", "Nairobi, Kenya", "Mombasa EAT"),
            CuratedTz("Africa/Casablanca", "Casablanca, Morocco", "Rabat WET"),
            CuratedTz("Africa/Accra", "Accra, Ghana", "GMT"),

            // South & Central Asia
            CuratedTz("Asia/Kolkata", "Kolkata / Mumbai / Delhi, India", "IST Bangalore Hyderabad Chennai Pune"),
            CuratedTz("Asia/Karachi", "Karachi / Islamabad, Pakistan", "PKT Lahore"),
            CuratedTz("Asia/Dhaka", "Dhaka, Bangladesh", "BST Chittagong"),
            CuratedTz("Asia/Colombo", "Colombo, Sri Lanka", "IST"),
            CuratedTz("Asia/Kathmandu", "Kathmandu, Nepal", "NPT"),
            CuratedTz("Asia/Tashkent", "Tashkent, Uzbekistan", "UZT"),

            // East & Southeast Asia
            CuratedTz("Asia/Singapore", "Singapore", "SGT"),
            CuratedTz("Asia/Tokyo", "Tokyo, Japan", "JST Osaka Kyoto"),
            CuratedTz("Asia/Seoul", "Seoul, South Korea", "KST Busan"),
            CuratedTz("Asia/Hong_Kong", "Hong Kong", "HKT"),
            CuratedTz("Asia/Shanghai", "Shanghai / Beijing, China", "CST Shenzhen Guangzhou"),
            CuratedTz("Asia/Taipei", "Taipei, Taiwan", "CST"),
            CuratedTz("Asia/Bangkok", "Bangkok, Thailand", "ICT Phuket Chiang Mai"),
            CuratedTz("Asia/Jakarta", "Jakarta, Indonesia", "WIB Java Sumatra"),
            CuratedTz("Asia/Makassar", "Bali / Denpasar, Indonesia", "WITA Makassar"),
            CuratedTz("Asia/Kuala_Lumpur", "Kuala Lumpur, Malaysia", "MYT Penang"),
            CuratedTz("Asia/Manila", "Manila, Philippines", "PHT Cebu"),
            CuratedTz("Asia/Ho_Chi_Minh", "Ho Chi Minh City, Vietnam", "Hanoi ICT"),

            // Australia & Pacific
            CuratedTz("Australia/Sydney", "Sydney, Australia", "AEST AEDT New South Wales Canberra"),
            CuratedTz("Australia/Melbourne", "Melbourne, Australia", "Victoria AEST AEDT"),
            CuratedTz("Australia/Brisbane", "Brisbane, Australia", "Queensland AEST"),
            CuratedTz("Australia/Adelaide", "Adelaide, Australia", "South Australia ACST ACDT"),
            CuratedTz("Australia/Perth", "Perth, Australia", "Western Australia AWST"),
            CuratedTz("Pacific/Auckland", "Auckland / Wellington, New Zealand", "NZST NZDT Christchurch"),
            CuratedTz("Pacific/Fiji", "Suva, Fiji", "FJT")
        )

        for (c in curated) {
            if (seenIds.add(c.id)) {
                allTimezones.add(createTimezoneItem(c.id, c.name, keywords = c.keywords))
            }
        }

        // 4. Append remaining standard timezones from the system
        val systemIds = TimeZone.getAvailableIDs()
            .filter { id ->
                !seenIds.contains(id) &&
                        (id.contains("/") && !id.startsWith("Etc/") && !id.startsWith("SystemV/"))
            }
            .sorted()

        for (id in systemIds) {
            val friendlyName = id.replace('_', ' ').replace("/", ", ")
            allTimezones.add(createTimezoneItem(id, friendlyName))
        }
    }

    private data class CuratedTz(val id: String, val name: String, val keywords: String)

    private fun createTimezoneItem(
        id: String,
        displayName: String,
        badge: String? = null,
        keywords: String = ""
    ): TimezoneItem {
        val tz = TimeZone.getTimeZone(id)
        val now = System.currentTimeMillis()
        val offsetMillis = tz.getOffset(now)
        val totalMinutes = offsetMillis / (1000 * 60)
        val hours = totalMinutes / 60
        val minutes = Math.abs(totalMinutes % 60)
        val sign = if (offsetMillis >= 0) "+" else "-"
        val offsetFormatted = String.format(Locale.US, "UTC%s%02d:%02d", sign, Math.abs(hours), minutes)
        val abbreviation = tz.getDisplayName(tz.inDaylightTime(Date(now)), TimeZone.SHORT, Locale.US)

        return TimezoneItem(
            id = id,
            displayName = displayName,
            offsetFormatted = offsetFormatted,
            abbreviation = abbreviation,
            badge = badge,
            searchKeywords = keywords
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class TimezoneAdapter(
        private var items: List<TimezoneItem>,
        private var currentSelected: String,
        private val onItemClick: (TimezoneItem) -> Unit
    ) : RecyclerView.Adapter<TimezoneAdapter.ViewHolder>() {

        fun updateItems(newItems: List<TimezoneItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(val binding: ItemTimezoneBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemTimezoneBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val isSelected = item.id == currentSelected

            holder.binding.tvCityName.text = item.displayName
            holder.binding.tvTimezoneIdAndOffset.text = "${item.id} • ${item.offsetFormatted} (${item.abbreviation})"

            if (!item.badge.isNullOrBlank()) {
                holder.binding.badgeTimezone.text = item.badge
                holder.binding.badgeTimezone.visibility = View.VISIBLE
            } else {
                holder.binding.badgeTimezone.visibility = View.GONE
            }

            holder.binding.ivSelectedCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.binding.root.alpha = if (isSelected) 1.0f else 0.9f

            holder.binding.root.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
