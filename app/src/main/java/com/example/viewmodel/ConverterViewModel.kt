package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.ConversionConfig
import com.example.model.ConversionStatus
import com.example.model.ImageItem
import com.example.utils.ImageConverter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConverterUiState(
    val images: List<ImageItem> = emptyList(),
    val config: ConversionConfig = ConversionConfig(),
    val isBatchConverting: Boolean = false,
    val batchProgress: Float = 0f,
    val currentConvertingIndex: Int = -1,
    val selectedDetailItem: ImageItem? = null,
    val showExifExplanation: Boolean = false,
    val toastMessage: String? = null
) {
    val totalOriginalSize: Long
        get() = images.sumOf { it.originalSizeBytes }

    val totalConvertedSize: Long
        get() = images.mapNotNull { it.convertedSizeBytes }.sum()

    val totalSavedSize: Long
        get() = if (totalConvertedSize > 0) (totalOriginalSize - totalConvertedSize).coerceAtLeast(0) else 0

    val convertedCount: Int
        get() = images.count { it.status is ConversionStatus.Success }

    val hasConvertedItems: Boolean
        get() = convertedCount > 0
}

class ConverterViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ConverterUiState())
    val uiState: StateFlow<ConverterUiState> = _uiState.asStateFlow()

    fun addImages(uris: List<Uri>, context: Context) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val newItems = uris.map { uri ->
                ImageConverter.inspectImage(context, uri)
            }
            _uiState.update { current ->
                // Avoid exact duplicate URIs
                val existingUris = current.images.map { it.uri }.toSet()
                val filtered = newItems.filter { it.uri !in existingUris }
                current.copy(
                    images = current.images + filtered,
                    toastMessage = "${filtered.size}枚の画像を追加しました"
                )
            }
        }
    }

    fun removeImage(id: String) {
        _uiState.update { current ->
            current.copy(images = current.images.filterNot { it.id == id })
        }
    }

    fun clearAll() {
        _uiState.update { it.copy(images = emptyList()) }
    }

    fun setLossless(isLossless: Boolean) {
        _uiState.update { current ->
            current.copy(
                config = current.config.copy(
                    isLossless = isLossless,
                    qualityPercent = if (isLossless) 100 else if (current.config.qualityPercent == 100) 80 else current.config.qualityPercent
                )
            )
        }
    }

    fun setQuality(quality: Int) {
        val clamped = quality.coerceIn(1, 100)
        _uiState.update { current ->
            current.copy(
                config = current.config.copy(
                    qualityPercent = clamped,
                    isLossless = if (clamped < 100) false else current.config.isLossless
                )
            )
        }
    }

    fun setPreserveExif(preserve: Boolean) {
        _uiState.update { current ->
            current.copy(config = current.config.copy(preserveExif = preserve))
        }
    }

    fun setScale(scale: Int) {
        _uiState.update { current ->
            current.copy(config = current.config.copy(scalePercent = scale.coerceIn(10, 100)))
        }
    }

    fun toggleExifExplanation(show: Boolean) {
        _uiState.update { it.copy(showExifExplanation = show) }
    }

    fun selectDetailItem(item: ImageItem?) {
        _uiState.update { it.copy(selectedDetailItem = item) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun convertSingle(context: Context, id: String) {
        val currentImages = _uiState.value.images
        val targetItem = currentImages.find { it.id == id } ?: return
        val config = _uiState.value.config

        viewModelScope.launch {
            updateItemStatus(id, ConversionStatus.Converting(0.1f))

            val result = ImageConverter.convertToWebP(context, targetItem, config) { prog ->
                updateItemStatus(id, ConversionStatus.Converting(prog))
            }

            result.fold(
                onSuccess = { (file, duration) ->
                    _uiState.update { state ->
                        val updated = state.images.map { item ->
                            if (item.id == id) {
                                item.copy(
                                    status = ConversionStatus.Success,
                                    convertedFile = file,
                                    convertedSizeBytes = file.length(),
                                    convertedQualityUsed = config.qualityPercent,
                                    convertedLosslessUsed = config.isLossless,
                                    convertedExifPreserved = config.preserveExif,
                                    conversionDurationMs = duration
                                )
                            } else item
                        }
                        state.copy(
                            images = updated,
                            toastMessage = "${targetItem.displayName} をWebPに変換しました"
                        )
                    }
                },
                onFailure = { error ->
                    updateItemStatus(id, ConversionStatus.Error(error.message ?: "変換に失敗しました"))
                }
            )
        }
    }

    fun convertAll(context: Context) {
        val itemsToConvert = _uiState.value.images
        if (itemsToConvert.isEmpty()) return

        val config = _uiState.value.config
        viewModelScope.launch {
            _uiState.update { it.copy(isBatchConverting = true, batchProgress = 0f) }

            val total = itemsToConvert.size
            for ((index, item) in itemsToConvert.withIndex()) {
                _uiState.update {
                    it.copy(
                        currentConvertingIndex = index,
                        batchProgress = index.toFloat() / total.toFloat()
                    )
                }

                updateItemStatus(item.id, ConversionStatus.Converting(0.1f))

                val result = ImageConverter.convertToWebP(context, item, config) { prog ->
                    updateItemStatus(item.id, ConversionStatus.Converting(prog))
                }

                result.fold(
                    onSuccess = { (file, duration) ->
                        _uiState.update { state ->
                            val updated = state.images.map { img ->
                                if (img.id == item.id) {
                                    img.copy(
                                        status = ConversionStatus.Success,
                                        convertedFile = file,
                                        convertedSizeBytes = file.length(),
                                        convertedQualityUsed = config.qualityPercent,
                                        convertedLosslessUsed = config.isLossless,
                                        convertedExifPreserved = config.preserveExif,
                                        conversionDurationMs = duration
                                    )
                                } else img
                            }
                            state.copy(images = updated)
                        }
                    },
                    onFailure = { error ->
                        updateItemStatus(item.id, ConversionStatus.Error(error.message ?: "失敗"))
                    }
                )
            }

            _uiState.update {
                it.copy(
                    isBatchConverting = false,
                    batchProgress = 1f,
                    currentConvertingIndex = -1,
                    toastMessage = "${total}枚の変換が完了しました！"
                )
            }
        }
    }

    private fun updateItemStatus(id: String, status: ConversionStatus) {
        _uiState.update { state ->
            val updated = state.images.map {
                if (it.id == id) it.copy(status = status) else it
            }
            state.copy(images = updated)
        }
    }

    fun saveSingleToGallery(context: Context, id: String) {
        val item = _uiState.value.images.find { it.id == id } ?: return
        val file = item.convertedFile ?: return

        viewModelScope.launch {
            val uri = ImageConverter.saveToGallery(context, file)
            if (uri != null) {
                _uiState.update { state ->
                    val updated = state.images.map {
                        if (it.id == id) it.copy(isSavedToGallery = true) else it
                    }
                    state.copy(
                        images = updated,
                        toastMessage = "ギャラリーの Pictures/WebPConverter に保存しました"
                    )
                }
            } else {
                _uiState.update { it.copy(toastMessage = "保存に失敗しました") }
            }
        }
    }

    fun saveAllToGallery(context: Context) {
        val items = _uiState.value.images.filter { it.convertedFile != null }
        if (items.isEmpty()) return

        viewModelScope.launch {
            var count = 0
            for (item in items) {
                val file = item.convertedFile ?: continue
                val uri = ImageConverter.saveToGallery(context, file)
                if (uri != null) {
                    count++
                    _uiState.update { state ->
                        val updated = state.images.map {
                            if (it.id == item.id) it.copy(isSavedToGallery = true) else it
                        }
                        state.copy(images = updated)
                    }
                }
            }
            _uiState.update {
                it.copy(toastMessage = "${count}枚のWebP画像をギャラリーに保存しました")
            }
        }
    }

    fun getShareIntent(context: Context, id: String? = null): Intent? {
        val files = if (id != null) {
            val item = _uiState.value.images.find { it.id == id }
            listOfNotNull(item?.convertedFile)
        } else {
            _uiState.value.images.mapNotNull { it.convertedFile }
        }

        if (files.isEmpty()) return null
        return ImageConverter.createShareIntent(context, files)
    }
}
