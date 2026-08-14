package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.BatchActionBottomBar
import com.example.ui.components.ExifInfoSheet
import com.example.ui.components.HeaderSection
import com.example.ui.components.ImageDetailDialog
import com.example.ui.components.ImageItemCard
import com.example.ui.components.PhotoPickerSection
import com.example.ui.components.SettingsCard
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.ConverterViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: ConverterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setOnExitAnimationListener { splashScreenView ->
            splashScreenView.iconView?.let { iconView ->
                iconView.animate()
                    .scaleX(1.18f)
                    .scaleY(1.18f)
                    .alpha(0f)
                    .setDuration(420L)
                    .withEndAction { splashScreenView.remove() }
                    .start()
            } ?: splashScreenView.remove()
        }
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                WebPConverterApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun WebPConverterApp(
    viewModel: ConverterViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Multi-select Photo Picker launcher
    val pickMultipleMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addImages(uris, context)
        }
    }

    // Single-select Photo Picker launcher
    val pickSingleMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.addImages(listOf(uri), context)
        }
    }

    // Fallback file picker launcher (ACTION_GET_CONTENT / ACTION_OPEN_DOCUMENT)
    val pickFiles = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addImages(uris, context)
        }
    }

    val isPhotoPickerAvailable = remember {
        ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)
    }

    fun launchMultiPicker() {
        if (isPhotoPickerAvailable) {
            try {
                pickMultipleMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (_: Exception) {
                pickFiles.launch("image/*")
            }
        } else {
            pickFiles.launch("image/*")
        }
    }

    fun launchSinglePicker() {
        if (isPhotoPickerAvailable) {
            try {
                pickSingleMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (_: Exception) {
                pickFiles.launch("image/*")
            }
        } else {
            pickFiles.launch("image/*")
        }
    }

    // Toast & Snackbar feedback
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearToast()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            AnimatedVisibility(
                visible = uiState.images.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                BatchActionBottomBar(
                    totalImages = uiState.images.size,
                    convertedCount = uiState.convertedCount,
                    totalOriginalSize = uiState.totalOriginalSize,
                    totalConvertedSize = uiState.totalConvertedSize,
                    isConverting = uiState.isBatchConverting,
                    batchProgress = uiState.batchProgress,
                    onConvertAll = { viewModel.convertAll(context) },
                    onSaveAll = { viewModel.saveAllToGallery(context) },
                    onShareAll = {
                        viewModel.getShareIntent(context)?.let { intent ->
                            context.startActivity(intent)
                        }
                    }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = if (uiState.images.isNotEmpty()) 100.dp else 24.dp)
        ) {
            // Header with App Title & Clear All
            item {
                HeaderSection(
                    imageCount = uiState.images.size,
                    totalSizeBytes = uiState.totalOriginalSize,
                    onClearAll = { viewModel.clearAll() }
                )
            }

            // Photo Picker Section (Dropzone & pickers)
            item {
                PhotoPickerSection(
                    onLaunchPhotoPickerMulti = { launchMultiPicker() },
                    onLaunchPhotoPickerSingle = { launchSinglePicker() },
                    onLaunchFilePicker = { pickFiles.launch("image/*") },
                    hasImages = uiState.images.isNotEmpty()
                )
            }

            // Compression & EXIF Settings Card
            item {
                SettingsCard(
                    config = uiState.config,
                    onLosslessChange = { viewModel.setLossless(it) },
                    onQualityChange = { viewModel.setQuality(it) },
                    onPreserveExifChange = { viewModel.setPreserveExif(it) },
                    onScaleChange = { viewModel.setScale(it) },
                    onShowExifInfo = { viewModel.toggleExifExplanation(true) }
                )
            }

            // Section header if images exist
            if (uiState.images.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "変換対象の画像 (${uiState.images.size}件)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        Text(
                            text = "${uiState.convertedCount} / ${uiState.images.size} 変換済",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Image items list with animated transitions
                items(
                    items = uiState.images,
                    key = { it.id }
                ) { item ->
                    ImageItemCard(
                        item = item,
                        onConvertSingle = { viewModel.convertSingle(context, item.id) },
                        onRemove = { viewModel.removeImage(item.id) },
                        onSaveToGallery = { viewModel.saveSingleToGallery(context, item.id) },
                        onShare = {
                            viewModel.getShareIntent(context, item.id)?.let { intent ->
                                context.startActivity(intent)
                            }
                        },
                        onOpenDetails = { viewModel.selectDetailItem(item) }
                    )
                }
            } else {
                // Empty state friendly card
                item {
                    EmptyStateCard(
                        onSelectImages = { launchMultiPicker() },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Image Detail & EXIF Inspector Dialog
        uiState.selectedDetailItem?.let { item ->
            ImageDetailDialog(
                item = item,
                onDismiss = { viewModel.selectDetailItem(null) },
                onSaveToGallery = {
                    viewModel.saveSingleToGallery(context, item.id)
                },
                onShare = {
                    viewModel.getShareIntent(context, item.id)?.let { intent ->
                        context.startActivity(intent)
                    }
                }
            )
        }

        // EXIF Explanation Modal Sheet
        if (uiState.showExifExplanation) {
            ExifInfoSheet(
                onDismiss = { viewModel.toggleExifExplanation(false) }
            )
        }
    }
}

@Composable
private fun EmptyStateCard(
    onSelectImages: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Collections,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "WebPフォーマットの特長",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "・JPEGより約25〜34%ファイルサイズを軽量化\n・PNGのような透過（アルファチャンネル）に対応\n・画質を落とさないロスレス圧縮もサポート\n・カメラのEXIFメタデータもそのまま引き継ぎ可能",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
