package com.pringor.qrcat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.pringor.qrcat.data.ScanWithOccurrences
import com.pringor.qrcat.scanner.QrAnalyzer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip

@Composable
fun ScanningScreen(
    viewModel: ScanViewModel,
    onNavigateToHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanMode by viewModel.scanMode.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()
    val multipleResults by viewModel.multipleResults.collectAsStateWithLifecycle()

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let { viewModel.scanImageFromUri(it) }
        }
    )

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor, QrAnalyzer { barcodes ->
                                val barcode = barcodes.firstOrNull()
                                if (barcode != null) {
                                    val content = barcode.rawValue ?: ""
                                    val type = when (barcode.valueType) {
                                        Barcode.TYPE_URL -> "URL"
                                        Barcode.TYPE_WIFI -> "WIFI"
                                        Barcode.TYPE_CONTACT_INFO -> "CONTACT"
                                        else -> "TEXT"
                                    }
                                    viewModel.onQrDetected(content, type)
                                }
                            })
                        }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (exc: Exception) {
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay UI
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Scanning Mode: ${scanMode.name}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small).padding(8.dp)
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { viewModel.setScanMode(ScanMode.SINGLE) }) { Text("Single") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { viewModel.setScanMode(ScanMode.CONTINUOUS) }) { Text("Continuous") }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconButton(
                onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.extraLarge)
            ) {
                Icon(Icons.Default.Image, contentDescription = "Import from Gallery")
            }
            Spacer(Modifier.height(16.dp))
            IconButton(
                onClick = onNavigateToHistory,
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge)
            ) {
                Icon(Icons.Default.History, contentDescription = "History")
            }
        }

        if (lastResult != null && scanMode == ScanMode.SINGLE) {
            ResultDialog(
                result = lastResult!!,
                onDismiss = { viewModel.clearLastResult() }
            )
        }

        if (multipleResults != null) {
            MultipleResultsDialog(
                results = multipleResults!!,
                onConfirm = { viewModel.addMultipleToLibrary(multipleResults!!) },
                onDismiss = { viewModel.clearMultipleResults() }
            )
        }
    }
}

@Composable
fun MultipleResultsDialog(
    results: List<ScanResult>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Multiple QR Codes Found") },
        text = {
            Column {
                Text("We have found ${results.size} QR codes in this image.")
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(results) { result ->
                        Text(
                            text = result.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Add All to Library")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    size: Int = 512
) {
    val bitmap = remember(content) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR Code",
            modifier = modifier
                .size(200.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    }
}

@Composable
fun ResultDialog(
    result: ScanResult,
    onDismiss: () -> Unit,
    showScanAgain: Boolean = true
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan Result") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                QrCodeImage(content = result.content)
                Spacer(Modifier.height(16.dp))
                
                when (result.type) {
                    "WIFI" -> WifiDetails(content = result.content)
                    "CONTACT" -> ContactDetails(content = result.content)
                    else -> {
                        Text(result.content, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        Text("Type: ${result.type}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TypeSpecificActions(result = result, context = context)
                
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("QR Scan", result.content)
                        clipboard.setPrimaryClip(clip)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Copy to Clipboard")
                }
                if (showScanAgain) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Scan Again")
                    }
                } else {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    )
}

@Composable
fun WifiDetails(content: String) {
    val ssid = content.substringAfter("S:", "").substringBefore(";")
    val password = content.substringAfter("P:", "").substringBefore(";")
    val type = content.substringAfter("T:", "").substringBefore(";")
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Network: $ssid", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Text("Security: $type", style = MaterialTheme.typography.bodyMedium)
        if (password.isNotEmpty()) {
            Text("Password: $password", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ContactDetails(content: String) {
    val lines = content.lines()
    val name = lines.find { it.startsWith("FN:", true) }?.substringAfter(":")
        ?: lines.find { it.startsWith("N:", true) }?.substringAfter(":")
        ?: "Contact"
    val phone = lines.find { it.startsWith("TEL", true) }?.substringAfterLast(":")
    val email = lines.find { it.startsWith("EMAIL", true) }?.substringAfterLast(":")
    val url = lines.find { it.startsWith("URL", true) }?.substringAfterLast(":")

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        phone?.let { Text("Phone: $it", style = MaterialTheme.typography.bodyMedium) }
        email?.let { Text("Email: $it", style = MaterialTheme.typography.bodyMedium) }
        url?.let { Text("Website: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
fun TypeSpecificActions(result: ScanResult, context: Context) {
    when (result.type) {
        "URL" -> {
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.content))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Link")
            }
            Spacer(Modifier.height(8.dp))
        }
        "WIFI" -> {
            val ssid = result.content.substringAfter("S:", "").substringBefore(";")
            val password = result.content.substringAfter("P:", "").substringBefore(";")
            val security = result.content.substringAfter("T:", "").substringBefore(";")

            Button(
                onClick = {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val suggestionBuilder = WifiNetworkSuggestion.Builder()
                                .setSsid(ssid)
                            
                            if (password.isNotEmpty()) {
                                if (security.contains("WPA", ignoreCase = true) || security.isEmpty()) {
                                    suggestionBuilder.setWpa2Passphrase(password)
                                } else if (security.contains("WPA3", ignoreCase = true)) {
                                    suggestionBuilder.setWpa3Passphrase(password)
                                }
                            }

                            val suggestions = arrayListOf(suggestionBuilder.build())
                            val bundle = Bundle().apply {
                                putParcelableArrayList("android.provider.extra.WIFI_NETWORK_LIST", suggestions)
                            }
                            val intent = Intent("android.settings.WIFI_ADD_NETWORKS").apply {
                                putExtras(bundle)
                            }
                            context.startActivity(intent)
                        } else {
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                            Toast.makeText(context, "Please select $ssid and paste the password", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect to Network")
            }
            Spacer(Modifier.height(8.dp))

            if (password.isNotEmpty()) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Wi-Fi Password", password)
                        clipboard.setPrimaryClip(clip)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Copy Password")
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        "CONTACT" -> {
            val lines = result.content.lines()
            val name = lines.find { it.startsWith("FN:", true) }?.substringAfter(":")
                ?: lines.find { it.startsWith("N:", true) }?.substringAfter(":")
                ?: "Contact"
            val phone = lines.find { it.startsWith("TEL", true) }?.substringAfterLast(":")
            val email = lines.find { it.startsWith("EMAIL", true) }?.substringAfterLast(":")
            val url = lines.find { it.startsWith("URL", true) }?.substringAfterLast(":")

            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        type = ContactsContract.Contacts.CONTENT_TYPE
                        putExtra(ContactsContract.Intents.Insert.NAME, name)
                        putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                        putExtra(ContactsContract.Intents.Insert.EMAIL, email)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add to Contacts")
            }
            Spacer(Modifier.height(8.dp))

            phone?.let { num ->
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Call")
                }
                Spacer(Modifier.height(8.dp))
            }

            email?.let { addr ->
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$addr"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Email")
                }
                Spacer(Modifier.height(8.dp))
            }

            url?.let { link ->
                Button(
                    onClick = {
                        val uri = if (link.startsWith("http")) Uri.parse(link) else Uri.parse("http://$link")
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Website")
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: ScanViewModel,
    onNavigateBack: () -> Unit
) {
    val history by viewModel.history.collectAsState(initial = emptyList())
    var selectedScan by remember { mutableStateOf<ScanWithOccurrences?>(null) }
    var expandedContents by remember { mutableStateOf(setOf<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Back to Scanner")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(history) { item ->
                val isExpanded = expandedContents.contains(item.scan.content)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable {
                            expandedContents = if (isExpanded) {
                                expandedContents - item.scan.content
                            } else {
                                expandedContents + item.scan.content
                            }
                        },
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = item.scan.title ?: "Untitled Scan", fontWeight = FontWeight.Bold)
                                Text(
                                    text = "${item.occurrences.size} scan(s)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = {
                                selectedScan = item
                            }) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = "View Details")
                            }
                            IconButton(onClick = { viewModel.deleteScan(item.scan) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }

                        if (isExpanded) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            item.occurrences.sortedByDescending { it.timestamp }.forEach { occurrence ->
                                Text(
                                    text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(occurrence.timestamp)),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedScan != null) {
        val latest = selectedScan!!.occurrences.maxByOrNull { it.timestamp }
        val result = ScanResult(selectedScan!!.scan.content, selectedScan!!.scan.type, latest?.timestamp ?: System.currentTimeMillis())
        ResultDialog(
            result = result,
            onDismiss = { selectedScan = null },
            showScanAgain = false
        )
    }
}

@Composable
fun PermissionRequestScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "QRCat needs camera access to scan codes.",
                modifier = Modifier.padding(16.dp)
            )
            Button(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
        }
    }
}