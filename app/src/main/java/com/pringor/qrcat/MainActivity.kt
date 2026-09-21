package com.pringor.qrcat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import com.google.android.gms.ads.MobileAds
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pringor.qrcat.ui.*
import com.pringor.qrcat.ui.theme.QRCatTheme

sealed class Screen(val route: String, val labelId: Int, val icon: ImageVector) {
    object Home : Screen("home", R.string.nav_home, Icons.Default.Home)
    object Scan : Screen("scan", R.string.nav_scan, Icons.Default.QrCodeScanner)
    object Generate : Screen("generate", R.string.nav_generate, Icons.Default.AddBox)
    object Library : Screen("library", R.string.nav_library, Icons.Default.LibraryBooks)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
}

class MainActivity : AppCompatActivity() {
    private val viewModel: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_QRCat)
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            val themeConfig by viewModel.themeConfig.collectAsState()
            val language by viewModel.language.collectAsState()

            // Official per-app language switching with check to prevent loops
            LaunchedEffect(language) {
                val targetTag = if (language == "Korean") "ko" else "en"
                val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                if (currentTags != targetTag) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(targetTag))
                }
            }

            QRCatTheme(darkTheme = when (themeConfig) {
                "Light" -> false
                "Dark" -> true
                else -> isSystemInDarkTheme()
            }) {
                val navController = rememberNavController()
                var hasCameraPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
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
                        
                        NavigationBar {
                            bottomNavItems.forEach { screen ->
                                val isSelected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                                
                                NavigationBarItem(
                                    icon = { Icon(screen.icon, contentDescription = null) },
                                    label = { Text(stringResource(screen.labelId)) },
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
                                HomeScreen(
                                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                                    viewModel = viewModel
                                )
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
                            composable(Screen.Settings.route) {
                                SettingsScreen(
                                    viewModel = viewModel,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }

                    // Global Result Dialogs
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