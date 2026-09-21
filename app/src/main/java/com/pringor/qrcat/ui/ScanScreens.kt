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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
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
import androidx.lifecycle.asFlow
import kotlinx.coroutines.flow.emptyFlow
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
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.delay
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

@Composable
fun AdaptiveBannerAd(modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp

    AndroidView(
        factory = { ctx ->
            AdView(ctx).apply {
                adUnitId = "ca-app-pub-3940256099942544/6300978111" // Test ID
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, screenWidth))
                loadAd(AdRequest.Builder().build())
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun ScanningScreen(
    viewModel: ScanViewModel,
    onNavigateToHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanMode by viewModel.scanMode.collectAsStateWithLifecycle()

    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var isTorchOn by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var isZooming by remember { mutableStateOf(false) }
    
    val cameraControl = remember { mutableStateOf<CameraControl?>(null) }
    val cameraInfo = remember { mutableStateOf<CameraInfo?>(null) }

    // Reset torch when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            cameraControl.value?.enableTorch(false)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let { viewModel.scanImageFromUri(it) }
        }
    )

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    cameraControl.value?.let { control ->
                        val currentRatio = cameraInfo.value?.zoomState?.value?.zoomRatio ?: 1f
                        val minRatio = cameraInfo.value?.zoomState?.value?.minZoomRatio ?: 1f
                        val maxRatio = cameraInfo.value?.zoomState?.value?.maxZoomRatio ?: 1f
                        
                        val newRatio = (currentRatio * zoom).coerceIn(minRatio, maxRatio)
                        control.setZoomRatio(newRatio)
                        
                        // Update indicator
                        zoomRatio = newRatio
                        isZooming = true
                    }
                }
            }
    ) {
        // Automatically hide zoom indicator after 2 seconds
        LaunchedEffect(isZooming) {
            if (isZooming) {
                delay(2000)
                isZooming = false
            }
        }

        key(cameraSelector) {
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

                        try {
                            cameraProvider.unbindAll()
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )
                            
                            cameraControl.value = camera.cameraControl
                            cameraInfo.value = camera.cameraInfo
                            
                            // SYNC FLASHLIGHT: Apply UI state to new camera hardware
                            if (camera.cameraInfo.hasFlashUnit()) {
                                camera.cameraControl.enableTorch(isTorchOn)
                            } else {
                                // Reset UI if new camera doesn't support flash
                                isTorchOn = false
                            }
                            
                        } catch (exc: Exception) {
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Overlay UI - Camera Controls
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Row {
                    // Flashlight Toggle
                    val hasFlash = cameraInfo.value?.hasFlashUnit() == true
                    if (hasFlash) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    val nextState = !isTorchOn
                                    cameraControl.value?.enableTorch(nextState)
                                    isTorchOn = nextState
                                }
                            ) {
                                Icon(
                                    imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                    contentDescription = "Toggle Flashlight",
                                    tint = if (isTorchOn) Color.Yellow else Color.White
                                )
                            }
                        }
                    }

                    // Camera Switch (Front/Back)
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                                    CameraSelector.DEFAULT_FRONT_CAMERA
                                } else {
                                    CameraSelector.DEFAULT_BACK_CAMERA
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlipCameraAndroid,
                                contentDescription = "Switch Camera",
                                tint = Color.White
                            )
                        }
                    }

                    // Scan Mode Toggle (Icon only)
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        IconButton(
                            onClick = {
                                viewModel.setScanMode(
                                    if (scanMode == ScanMode.SINGLE) ScanMode.CONTINUOUS else ScanMode.SINGLE
                                )
                            }
                        ) {
                            Icon(
                                imageVector = if (scanMode == ScanMode.SINGLE) Icons.Default.Filter1 else Icons.Default.AllInclusive,
                                contentDescription = "Toggle Scan Mode",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Zoom Indicator (Fading)
            val alpha by animateFloatAsState(targetValue = if (isZooming) 1f else 0f)
            if (alpha > 0f) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f * alpha),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = String.format(Locale.getDefault(), "%.1fx", zoomRatio),
                        color = Color.White.copy(alpha = alpha),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
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
        ?: lines.find { it.startsWith("N:", true) }?.substringAfter(":")?.replace(";", " ")?.trim()
        ?: "Contact"
    val phone = lines.find { it.startsWith("TEL", true) }?.substringAfterLast(":")?.trim()
    val email = lines.find { it.startsWith("EMAIL", true) }?.substringAfterLast(":")?.trim()
    val url = lines.find { it.startsWith("URL", true) }?.substringAfterLast(":")?.trim()
    val org = lines.find { it.startsWith("ORG:", true) }?.substringAfter(":")?.substringBefore(";")?.trim()
    val title = lines.find { it.startsWith("TITLE:", true) }?.substringAfter(":")?.trim()
    val address = lines.find { it.startsWith("ADR", true) }?.substringAfterLast(":")?.replace(";", " ")?.trim()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        if (!title.isNullOrEmpty()) Text(title, style = MaterialTheme.typography.bodyMedium)
        if (!org.isNullOrEmpty()) Text(org, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
        
        Spacer(Modifier.height(8.dp))
        
        phone?.let { Text("Phone: $it", style = MaterialTheme.typography.bodyMedium) }
        email?.let { Text("Email: $it", style = MaterialTheme.typography.bodyMedium) }
        url?.let { Text("Website: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        if (!address.isNullOrEmpty()) Text("Address: $address", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
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
                ?: lines.find { it.startsWith("N:", true) }?.substringAfter(":")?.replace(";", " ")?.trim()
                ?: "Contact"
            val phone = lines.find { it.startsWith("TEL", true) }?.substringAfterLast(":")?.trim()
            val email = lines.find { it.startsWith("EMAIL", true) }?.substringAfterLast(":")?.trim()
            val url = lines.find { it.startsWith("URL", true) }?.substringAfterLast(":")?.trim()
            val org = lines.find { it.startsWith("ORG:", true) }?.substringAfter(":")?.substringBefore(";")?.trim()
            val title = lines.find { it.startsWith("TITLE:", true) }?.substringAfter(":")?.trim()
            val address = lines.find { it.startsWith("ADR", true) }?.substringAfterLast(":")?.replace(";", " ")?.trim()
            val notesField = lines.find { it.startsWith("NOTE:", true) }?.substringAfter(":")?.trim()

            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        type = ContactsContract.Contacts.CONTENT_TYPE
                        putExtra(ContactsContract.Intents.Insert.NAME, name)
                        putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                        putExtra(ContactsContract.Intents.Insert.EMAIL, email)
                        putExtra(ContactsContract.Intents.Insert.COMPANY, org)
                        putExtra(ContactsContract.Intents.Insert.JOB_TITLE, title)
                        putExtra(ContactsContract.Intents.Insert.POSTAL, address)
                        
                        val fullNotes = buildString {
                            if (!notesField.isNullOrEmpty()) append(notesField)
                            if (!url.isNullOrEmpty()) {
                                if (isNotEmpty()) append("\n")
                                append("Website: $url")
                            }
                        }
                        if (fullNotes.isNotEmpty()) {
                            putExtra(ContactsContract.Intents.Insert.NOTES, fullNotes)
                        }
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
fun LibraryScreen(
    viewModel: ScanViewModel,
    onNavigateToCamera: () -> Unit
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val typeFilter by viewModel.typeFilter.collectAsStateWithLifecycle()

    var selectedScan by remember { mutableStateOf<ScanWithOccurrences?>(null) }
    var expandedContents by remember { mutableStateOf(setOf<String>()) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Library") },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear All")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search and Filters
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search scans...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Type Filter
                Box(modifier = Modifier.weight(1f)) {
                    var filterExpanded by remember { mutableStateOf(false) }
                    FilterChip(
                        selected = typeFilter != "All",
                        onClick = { filterExpanded = true },
                        label = { Text(if (typeFilter == "All") "Filter Type" else "Type: $typeFilter") },
                        leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null) }
                    )
                    DropdownMenu(
                        expanded = filterExpanded,
                        onDismissRequest = { filterExpanded = false }
                    ) {
                        listOf("All", "URL", "TEXT", "WIFI", "CONTACT").forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = {
                                    viewModel.setTypeFilter(type)
                                    filterExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Sort Order
                FilterChip(
                    selected = true,
                    onClick = {
                        viewModel.setSortOrder(
                            if (sortOrder == SortOrder.NEWEST) SortOrder.OLDEST else SortOrder.NEWEST
                        )
                    },
                    label = { Text(if (sortOrder == SortOrder.NEWEST) "Newest First" else "Oldest First") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) }
                )
            }

            if (history.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No scans found", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(history) { item ->
                        val isExpanded = expandedContents.contains(item.scan.content)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
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
                                            text = "${item.occurrences.size} scan(s) • ${item.scan.type}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    IconButton(onClick = { selectedScan = item }) {
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
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear All History") },
            text = { Text("Are you sure you want to delete all scans? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNavigateToSettings: () -> Unit) {
    var languageExpanded by remember { mutableStateOf(false) }
    var selectedLanguage by remember { mutableStateOf("English") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QRCat") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                OutlinedButton(onClick = { languageExpanded = true }) {
                    Icon(Icons.Default.Language, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(selectedLanguage)
                }
                DropdownMenu(
                    expanded = languageExpanded,
                    onDismissRequest = { languageExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("English") },
                        onClick = {
                            selectedLanguage = "English"
                            languageExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Korean (한국어)") },
                        onClick = {
                            selectedLanguage = "Korean (한국어)"
                            languageExpanded = false
                        }
                    )
                }
            }
            
            Spacer(Modifier.height(32.dp))
            
            Text(
                "Welcome to QRCat",
                style = MaterialTheme.typography.headlineSmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanHubScreen(
    onNavigateToCamera: () -> Unit,
    viewModel: ScanViewModel
) {
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let { viewModel.scanImageFromUri(it) }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Scan") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onNavigateToCamera,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Camera Scan", style = MaterialTheme.typography.titleMedium)
            }
            
            Spacer(Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Gallery Scan", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateScreen(viewModel: ScanViewModel) {
    var selectedType by remember { mutableStateOf<String?>(null) }
    
    // Form States
    var url by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var wifiSecurity by remember { mutableStateOf("WPA") }
    
    var contactName by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var contactEmail by remember { mutableStateOf("") }
    var contactOrg by remember { mutableStateOf("") }
    var contactUrl by remember { mutableStateOf("") }

    var generatedPayload by remember { mutableStateOf<String?>(null) }

    if (generatedPayload != null) {
        QrPreviewScreen(
            payload = generatedPayload!!,
            onBack = { generatedPayload = null },
            viewModel = viewModel
        )
    } else if (selectedType == null) {
        TypeSelectionScreen(onTypeSelected = { selectedType = it })
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Generate $selectedType") },
                    navigationIcon = {
                        IconButton(onClick = { selectedType = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (selectedType) {
                    "URL" -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("Website URL") },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("https://example.com") }
                        )
                    }
                    "Text" -> {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("Plain Text") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                    }
                    "Wi-Fi" -> {
                        OutlinedTextField(
                            value = wifiSsid,
                            onValueChange = { wifiSsid = it },
                            label = { Text("Network Name (SSID)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = wifiPassword,
                            onValueChange = { wifiPassword = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Security Type", style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.Start))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(selected = wifiSecurity == "WPA", onClick = { wifiSecurity = "WPA" })
                            Text("WPA/WPA2")
                            Spacer(Modifier.width(16.dp))
                            RadioButton(selected = wifiSecurity == "WEP", onClick = { wifiSecurity = "WEP" })
                            Text("WEP")
                            Spacer(Modifier.width(16.dp))
                            RadioButton(selected = wifiSecurity == "nopass", onClick = { wifiSecurity = "nopass" })
                            Text("None")
                        }
                    }
                    "Contact" -> {
                        OutlinedTextField(value = contactName, onValueChange = { contactName = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = contactPhone, onValueChange = { contactPhone = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = contactEmail, onValueChange = { contactEmail = it }, label = { Text("Email Address") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = contactOrg, onValueChange = { contactOrg = it }, label = { Text("Organization") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = contactUrl, onValueChange = { contactUrl = it }, label = { Text("Website") }, modifier = Modifier.fillMaxWidth())
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = {
                        generatedPayload = when (selectedType) {
                            "URL" -> if (url.startsWith("http")) url else "https://$url"
                            "Text" -> text
                            "Wi-Fi" -> "WIFI:T:$wifiSecurity;S:$wifiSsid;P:$wifiPassword;;"
                            "Contact" -> "BEGIN:VCARD\nVERSION:3.0\nFN:$contactName\nORG:$contactOrg\nTEL:$contactPhone\nEMAIL:$contactEmail\nURL:$contactUrl\nEND:VCARD"
                            else -> ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = when (selectedType) {
                        "URL" -> url.isNotEmpty()
                        "Text" -> text.isNotEmpty()
                        "Wi-Fi" -> wifiSsid.isNotEmpty()
                        "Contact" -> contactName.isNotEmpty()
                        else -> false
                    }
                ) {
                    Text("Generate QR Code")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeSelectionScreen(onTypeSelected: (String) -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Generate QR") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val types = listOf(
                "URL" to Icons.Default.Link,
                "Text" to Icons.Default.Notes,
                "Wi-Fi" to Icons.Default.Wifi,
                "Contact" to Icons.Default.Person
            )
            
            types.forEach { (label, icon) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clickable { onTypeSelected(label) },
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrPreviewScreen(
    payload: String,
    onBack: () -> Unit,
    viewModel: ScanViewModel
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QR Preview") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val bitmap = remember(payload) { generateQrBitmap(payload) }
            
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Generated QR Code",
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(16.dp)
                )
                
                Spacer(Modifier.height(32.dp))
                
                Button(
                    onClick = { viewModel.saveBitmapToGallery(bitmap) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save to Gallery")
                }
                
                Spacer(Modifier.height(8.dp))
                
                OutlinedButton(
                    onClick = { viewModel.shareBitmap(bitmap) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share Image")
                }
            } else {
                Text("Error generating QR code", color = MaterialTheme.colorScheme.error)
            }
            
            Spacer(Modifier.weight(1f))
            
            Text(
                "Previewing content:",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Gray
            )
            Text(
                payload,
                maxLines = 2,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ScanViewModel,
    onNavigateBack: () -> Unit
) {
    val vibrationEnabled by viewModel.vibrationEnabled.collectAsStateWithLifecycle()
    val themeConfig by viewModel.themeConfig.collectAsStateWithLifecycle()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Support Section
            SupportSection()

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Appearance Section
            SettingsSectionHeader("Appearance")
            ThemeSelectionRow(
                currentTheme = themeConfig,
                onThemeSelected = { viewModel.setThemeConfig(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Scanning Section
            SettingsSectionHeader("Scanning")
            SettingsToggleRow(
                title = "Vibration on Scan",
                checked = vibrationEnabled,
                onCheckedChange = { viewModel.setVibrationEnabled(it) },
                icon = Icons.Default.Vibration
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // About Section
            SettingsSectionHeader("About")
            SettingsClickableRow(title = "Privacy Policy", onClick = { /* TODO */ })
            SettingsClickableRow(title = "Terms of Service", onClick = { /* TODO */ })
            SettingsClickableRow(title = "Open Source Licenses", onClick = { /* TODO */ })
            
            Spacer(Modifier.height(16.dp))
            Text(
                text = "App Version: 1.0.0",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun SupportSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Mascot Placeholder
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Pets, 
                contentDescription = null, 
                modifier = Modifier.size(60.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        
        Spacer(Modifier.height(16.dp))
        
        Text(
            "Support QRCat",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Remove Ads + Buy Me a Treat",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )
        
        Spacer(Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("$1", "$5", "$20").forEach { price ->
                Button(
                    onClick = { /* Mock Purchase Logic */ },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(price)
                }
            }
        }
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun ThemeSelectionRow(
    currentTheme: String,
    onThemeSelected: (String) -> Unit
) {
    Column {
        listOf("System", "Light", "Dark").forEach { theme ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onThemeSelected(theme) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = currentTheme == theme,
                    onClick = { onThemeSelected(theme) }
                )
                Spacer(Modifier.width(8.dp))
                Text(theme)
            }
        }
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.Gray)
        Spacer(Modifier.width(16.dp))
        Text(text = title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingsClickableRow(
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.Gray)
    }
}

private fun generateQrBitmap(content: String, size: Int = 512): Bitmap? {
    return try {
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