package com.apadev.memoryfix.model

data class ScanStats(
    val totalMediaFiles: Int = 0,
    val photoCount: Int = 0,
    val videoCount: Int = 0,
    val matchedCount: Int = 0,
    val overlayCount: Int = 0,
    val duplicateCount: Int = 0,
    val missingMetadataCount: Int = 0,
    val detectedTimezone: String = "UTC",
    val estimatedTimeSeconds: Int = 0,
    val totalMetadataEntries: Int = 0,
    val hasMetadataFile: Boolean = false
)

data class ProcessingOptions(
    val restoreDates: Boolean = true,
    val embedLocation: Boolean = true,
    val mergeOverlays: Boolean = true,
    val separateDuplicates: Boolean = true,
    val organizeByMonth: Boolean = false,
    val targetTimeZone: String = "UTC"
)

data class ProcessProgress(
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val currentFileName: String = "",
    val statusText: String = "",
    val isComplete: Boolean = false,
    val error: String? = null
)
