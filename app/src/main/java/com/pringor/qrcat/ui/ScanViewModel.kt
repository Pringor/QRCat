package com.pringor.qrcat.ui

import android.app.Application
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import android.content.ContentValues
import android.provider.MediaStore

enum class ScanMode {
    SINGLE, CONTINUOUS
}

enum class SortOrder {
    NEWEST, OLDEST
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

    private val _isAdsEnabled = MutableStateFlow(true)
    val isAdsEnabled: StateFlow<Boolean> = _isAdsEnabled.asStateFlow()

    private val _scanMode = MutableStateFlow(ScanMode.SINGLE)
    val scanMode: StateFlow<ScanMode> = _scanMode.asStateFlow()

    private val _lastResult = MutableStateFlow<ScanResult?>(null)
    val lastResult: StateFlow<ScanResult?> = _lastResult.asStateFlow()

    private val _multipleResults = MutableStateFlow<List<ScanResult>?>(null)
    val multipleResults: StateFlow<List<ScanResult>?> = _multipleResults.asStateFlow()

    private val seenContents = mutableSetOf<String>()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.NEWEST)
    val sortOrder = _sortOrder.asStateFlow()

    private val _typeFilter = MutableStateFlow("All")
    val typeFilter = _typeFilter.asStateFlow()

    val history = combine(
        scanDao.getAllScansWithOccurrences(),
        _searchQuery,
        _sortOrder,
        _typeFilter
    ) { scans, query, sort, type ->
        scans.filter { item ->
            val matchesQuery = item.scan.content.contains(query, ignoreCase = true) || 
                             (item.scan.title?.contains(query, ignoreCase = true) == true)
            val matchesType = type == "All" || item.scan.type == type
            matchesQuery && matchesType
        }.sortedWith { a, b ->
            val timeA = a.occurrences.maxByOrNull { it.timestamp }?.timestamp ?: 0L
            val timeB = b.occurrences.maxByOrNull { it.timestamp }?.timestamp ?: 0L
            if (sort == SortOrder.NEWEST) timeB.compareTo(timeA) else timeA.compareTo(timeB)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setTypeFilter(type: String) {
        _typeFilter.value = type
    }

    fun setScanMode(mode: ScanMode) {
        _scanMode.value = mode
        seenContents.clear()
    }

    fun onQrDetected(content: String, type: String) {
        viewModelScope.launch {
            if (_scanMode.value == ScanMode.SINGLE && _lastResult.value != null) return@launch
            if (_scanMode.value == ScanMode.CONTINUOUS && seenContents.contains(content)) return@launch

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
            
            viewModelScope.launch(Dispatchers.IO) {
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
    }

    fun scanImageFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.IO) {
                    InputImage.fromFilePath(getApplication(), uri)
                }
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

    fun clearAllHistory() {
        viewModelScope.launch {
            scanDao.deleteAllScans()
        }
    }

    fun shareBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                val cachePath = File(getApplication<Application>().cacheDir, "shared_images")
                cachePath.mkdirs()
                val file = File(cachePath, "generated_qr_${System.currentTimeMillis()}.png")
                val stream = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.close()

                val uri = FileProvider.getUriForFile(
                    getApplication(),
                    "${getApplication<Application>().packageName}.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "Share QR Code")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "Failed to share image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveBitmapToGallery(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val filename = "QRCat_${System.currentTimeMillis()}.png"
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val resolver = getApplication<Application>().contentResolver
                val uri = resolver.insert(collection, values)

                uri?.let {
                    resolver.openOutputStream(it)?.use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(it, values, null, null)
                    }
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Image saved to gallery", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Failed to save image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
