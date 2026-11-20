package ru.d00g00d.apkstore.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.d00g00d.apkstore.data.model.AppModel
import ru.d00g00d.apkstore.data.repository.AppRepository
import ru.d00g00d.apkstore.downloader.ApkDownloader

data class MainUiState(
    val isLoading: Boolean = false,
    val apps: List<AppModel> = emptyList(),
    val error: String? = null
)

class MainViewModel(
    private val repository: AppRepository,
    private val downloader: ApkDownloader
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        loadApps()
    }

    fun loadApps() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val result = repository.getApps()
            result.onSuccess { apps ->
                _uiState.value = _uiState.value.copy(isLoading = false, apps = apps)
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = err.message ?: "Unknown error")
            }
        }
    }

    fun downloadApp(app: AppModel) {
        val fileName = "${app.id}_${app.version}.apk"
        downloader.downloadApk(app.apkUrl, fileName)
    }
}
