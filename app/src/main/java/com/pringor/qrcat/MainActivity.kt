package com.pringor.qrcat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import com.google.android.gms.ads.MobileAds
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pringor.qrcat.ui.*
import com.pringor.qrcat.ui.theme.QRCatTheme

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Scan : Screen("scan", "Scan", Icons.Default.QrCodeScanner)
    object Generate : Screen("generate", "Generate", Icons.Default.AddBox)
    object Library : Screen("library", "Library", Icons.Default.LibraryBooks)
}

class MainActivity : ComponentActivity() {
    private val viewModel: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            QRCatTheme {
                val navController = rememberNavController()
                var hasCameraPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                val launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { granted ->
                        hasCameraPermission = granted
                    }
                )

                val bottomNavItems = listOf(Screen.Home, Screen.Scan, Screen.Generate, Screen.Library)
                val isAdsEnabled by viewModel.isAdsEnabled.collectAsState()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        val navBackStackEntry by navController.currentBackStackEntryAsState()
                        val currentDestination = navBackStackEntry?.destination
                        
                        // Show bottom bar for all main tabs and the camera scanner
                        val showBottomBar = true 
                        
                        if (showBottomBar) {
                            NavigationBar {
                                bottomNavItems.forEach { screen ->
                                    val isSelected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                                    
                                    NavigationBarItem(
                                        icon = { Icon(screen.icon, contentDescription = null) },
                                        label = { Text(screen.label) },
                                        selected = isSelected,
                                        onClick = {
                                            if (screen.route == Screen.Home.route) {
                                                navController.navigate(screen.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        inclusive = true
                                                    }
                                                    launchSingleTop = true
                                                }
                                            } else if (screen.route == Screen.Scan.route) {
                                                navController.navigate(screen.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = false
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = false
                                                }
                                            } else {
                                                navController.navigate(screen.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (isAdsEnabled) {
                            AdaptiveBannerAd()
                        }
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Home.route,
                            modifier = Modifier.weight(1f)
                        ) {
                            composable(Screen.Home.route) {
                                HomeScreen()
                            }
                            composable(Screen.Scan.route) {
                                ScanHubScreen(
                                    onNavigateToCamera = {
                                        navController.navigate("camera_scanner")
                                    },
                                    viewModel = viewModel
                                )
                            }
                            composable("camera_scanner") {
                                if (hasCameraPermission) {
                                    ScanningScreen(
                                        viewModel = viewModel,
                                        onNavigateToHistory = {
                                            navController.popBackStack()
                                        }
                                    )
                                } else {
                                    PermissionRequestScreen(
                                        onRequestPermission = {
                                            launcher.launch(Manifest.permission.CAMERA)
                                        }
                                    )
                                }
                            }
                            composable(Screen.Generate.route) {
                                GenerateScreen(viewModel = viewModel)
                            }
                            composable(Screen.Library.route) {
                                LibraryScreen(
                                    viewModel = viewModel,
                                    onNavigateToCamera = { navController.navigate("camera_scanner") }
                                )
                            }
                        }
                    }

                    // Global Result Dialogs - Handle Camera, Gallery, and Share results anywhere
                    val lastResult by viewModel.lastResult.collectAsState()
                    val multipleResults by viewModel.multipleResults.collectAsState()
                    val scanMode by viewModel.scanMode.collectAsState()

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
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
            }
            imageUri?.let { viewModel.scanImageFromUri(it) }
        }
    }
}