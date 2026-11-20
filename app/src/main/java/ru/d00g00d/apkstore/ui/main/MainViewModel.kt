package ru.d00g00d.apkstore.ui.main

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.d00g00d.apkstore.data.model.AppModel
import ru.d00g00d.apkstore.data.repository.AppRepository
import ru.d00g00d.apkstore.downloader.ApkDownloader
import ru.d00g00d.apkstore.downloader.DownloadTracker

enum class AppStatus {
    DOWNLOAD, DOWNLOADING, UPDATE, OPEN
}

data class AppItemState(
    val app: AppModel,
    val status: AppStatus = AppStatus.DOWNLOAD,
    val progress: Int = 0,
    val downloadId: Long = -1
)

data class MainUiState(
    val isLoading: Boolean = false,
    val apps: List<AppItemState> = emptyList(),
    val error: String? = null,
    val searchQuery: String = ""
)

class MainViewModel(
    private val repository: AppRepository,
    private val downloader: ApkDownloader,
    private val tracker: DownloadTracker,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    private val allApps = mutableListOf<AppModel>()

    init {
        loadApps()
    }

    fun loadApps() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = repository.getApps()
            result.onSuccess { apps ->
                allApps.clear()
                allApps.addAll(apps)
                updateList()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, error = err.message ?: "Unknown error") }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        updateList()
    }

    private fun updateList() {
        val query = _uiState.value.searchQuery.lowercase()
        val filtered = if (query.isBlank()) allApps else allApps.filter { it.name.lowercase().contains(query) }
        
        val pm = context.packageManager
        
        val itemStates = filtered.map { app ->
            var status = AppStatus.DOWNLOAD
            try {
                val pInfo = pm.getPackageInfo(app.packageName, 0)
                // Очень упрощенная проверка версии (считаем, что если есть буквы, то надо смотреть на code)
                // В реальном приложении нужно парсить аккуратно, пока просто для примера если ==, то OPEN
                if (pInfo.versionName == app.version) {
                    status = AppStatus.OPEN
                } else {
                    status = AppStatus.UPDATE
                }
            } catch (e: PackageManager.NameNotFoundException) {
                status = AppStatus.DOWNLOAD
            }
            
            AppItemState(app, status = status)
        }
        _uiState.update { it.copy(isLoading = false, apps = itemStates) }
    }

    fun downloadApp(item: AppItemState) {
        val fileName = "${item.app.id}_${item.app.version}.apk"
        val downloadId = downloader.downloadApk(item.app.apkUrl, fileName)
        if (downloadId != -1L) {
            // Обновляем статус
            _uiState.update { state ->
                val newApps = state.apps.map { 
                    if (it.app.id == item.app.id) it.copy(status = AppStatus.DOWNLOADING, downloadId = downloadId, progress = 0) 
                    else it 
                }
                state.copy(apps = newApps)
            }
            
            // Начинаем трекать
            viewModelScope.launch {
                tracker.getProgress(downloadId).collect { progress ->
                    _uiState.update { state ->
                        val newApps = state.apps.map { 
                            if (it.app.id == item.app.id) {
                                if (progress == 100 || progress == -1) {
                                    // Установка пойдет сама через Receiver. 
                                    // Мы пока просто вернем статус к исходному, или после установки он сам поменяется
                                    // когда обновится список (можно сделать ReceiverFlow для обновления списка).
                                    // Пока оставим прогресс 100%.
                                    it.copy(progress = progress)
                                } else {
                                    it.copy(progress = progress)
                                }
                            } else it 
                        }
                        state.copy(apps = newApps)
                    }
                }
            }
        }
    }

    fun openApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
