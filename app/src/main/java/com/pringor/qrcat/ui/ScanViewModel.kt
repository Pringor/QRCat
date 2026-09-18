package com.pringor.qrcat.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.pringor.qrcat.data.AppDatabase
import com.pringor.qrcat.data.ScanEntity
import com.pringor.qrcat.data.ScanOccurrenceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ScanMode {
    SINGLE, CONTINUOUS
}

data class ScanResult(
    val content: String,
    val type: String,
    val timestamp: Long = System.currentTimeMillis()
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val scanDao = AppDatabase.getDatabase(application).scanDao()

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    private val _scanMode = MutableStateFlow(ScanMode.SINGLE)
    val scanMode: StateFlow<ScanMode> = _scanMode.asStateFlow()

    private val _lastResult = MutableStateFlow<ScanResult?>(null)
    val lastResult: StateFlow<ScanResult?> = _lastResult.asStateFlow()

    private val _multipleResults = MutableStateFlow<List<ScanResult>?>(null)
    val multipleResults: StateFlow<List<ScanResult>?> = _multipleResults.asStateFlow()

    private val seenContents = mutableSetOf<String>()

    val history = scanDao.getAllScansWithOccurrences()

    fun setScanMode(mode: ScanMode) {
        _scanMode.value = mode
        seenContents.clear()
    }

    fun onQrDetected(content: String, type: String) {
        if (_scanMode.value == ScanMode.SINGLE && _lastResult.value != null) return
        if (_scanMode.value == ScanMode.CONTINUOUS && seenContents.contains(content)) return

        val timestamp = System.currentTimeMillis()
        val result = ScanResult(content, type, timestamp)
        
        val title = when (type) {
            "URL" -> content
            "WIFI" -> {
                val ssid = content.substringAfter("S:", "").substringBefore(";")
                "Wi-Fi: $ssid"
            }
            "CONTACT" -> {
                val name = content.lines().firstOrNull { it.startsWith("FN:", true) }?.removePrefix("FN:")
                    ?: content.lines().firstOrNull { it.startsWith("N:", true) }?.removePrefix("N:")
                    ?: "Contact"
                "Contact: $name"
            }
            else -> "Text Scan"
        }
        
        if (_scanMode.value == ScanMode.SINGLE) {
            _lastResult.value = result
        } else {
            seenContents.add(content)
            Toast.makeText(getApplication(), "Scanned \"$title\"", Toast.LENGTH_SHORT).show()
        }
        
        triggerVibration()
        
        viewModelScope.launch {
            val scanEntity = ScanEntity(
                content = content,
                type = type,
                title = title
            )
            scanDao.insertScan(scanEntity)
            scanDao.insertOccurrence(
                ScanOccurrenceEntity(
                    scanContent = content,
                    timestamp = timestamp
                )
            )
        }
    }

    fun scanImageFromUri(uri: Uri) {
        try {
            val image = InputImage.fromFilePath(getApplication(), uri)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isEmpty()) {
                        Toast.makeText(getApplication(), "No QR code found in image", Toast.LENGTH_SHORT).show()
                    } else if (barcodes.size > 1) {
                        val results = barcodes.map { barcode ->
                            val content = barcode.rawValue ?: ""
                            val type = when (barcode.valueType) {
                                Barcode.TYPE_URL -> "URL"
                                Barcode.TYPE_WIFI -> "WIFI"
                                Barcode.TYPE_CONTACT_INFO -> "CONTACT"
                                else -> "TEXT"
                            }
                            ScanResult(content, type)
                        }
                        _multipleResults.value = results
                    } else {
                        val barcode = barcodes[0]
                        val content = barcode.rawValue ?: ""
                        val type = when (barcode.valueType) {
                            Barcode.TYPE_URL -> "URL"
                            Barcode.TYPE_WIFI -> "WIFI"
                            Barcode.TYPE_CONTACT_INFO -> "CONTACT"
                            else -> "TEXT"
                        }
                        onQrDetected(content, type)
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(getApplication(), "Failed to scan image", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(getApplication(), "Error loading image", Toast.LENGTH_SHORT).show()
        }
    }

    fun addMultipleToLibrary(results: List<ScanResult>) {
        viewModelScope.launch {
            results.forEach { result ->
                val title = when (result.type) {
                    "URL" -> result.content
                    "WIFI" -> {
                        val ssid = result.content.substringAfter("S:", "").substringBefore(";")
                        "Wi-Fi: $ssid"
                    }
                    "CONTACT" -> {
                        val name = result.content.lines().firstOrNull { it.startsWith("FN:", true) }?.removePrefix("FN:")
                            ?: result.content.lines().firstOrNull { it.startsWith("N:", true) }?.removePrefix("N:")
                            ?: "Contact"
                        "Contact: $name"
                    }
                    else -> "Text Scan"
                }
                scanDao.insertScan(
                    ScanEntity(
                        content = result.content,
                        type = result.type,
                        title = title
                    )
                )
                scanDao.insertOccurrence(
                    ScanOccurrenceEntity(
                        scanContent = result.content,
                        timestamp = result.timestamp
                    )
                )
            }
            triggerVibration()
            Toast.makeText(getApplication(), "Added ${results.size} codes to library", Toast.LENGTH_SHORT).show()
            _multipleResults.value = null
        }
    }

    fun clearMultipleResults() {
        _multipleResults.value = null
    }

    private fun triggerVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getApplication<Application>().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    fun clearLastResult() {
        _lastResult.value = null
    }

    fun deleteScan(scan: ScanEntity) {
        viewModelScope.launch {
            scanDao.deleteScan(scan)
        }
    }
}
