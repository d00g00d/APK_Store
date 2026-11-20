package ru.d00g00d.apkstore.ui.local

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.d00g00d.apkstore.core.permission.PermissionEntry
import ru.d00g00d.apkstore.core.permission.PermissionManager

data class LocalAppItem(
    val packageName: String,
    val name: String,
    val version: String,
    val isSystem: Boolean,
    val userId: Int
)

data class LocalAppsUiState(
    val isLoading: Boolean = true,
    val thirdPartyApps: List<LocalAppItem> = emptyList(),
    val systemApps: List<LocalAppItem> = emptyList(),
    val searchQuery: String = "",
    val selectedApp: LocalAppItem? = null,
    val permissions: List<PermissionEntry> = emptyList(),
    val isPermissionsLoading: Boolean = false
)

class LocalAppsViewModel(
    private val context: Context,
    private val permissionManager: PermissionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalAppsUiState())
    val uiState: StateFlow<LocalAppsUiState> = _uiState.asStateFlow()

    private val allApps = mutableListOf<LocalAppItem>()

    init {
        loadApps()
    }

    fun loadApps() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val packages = pm.getInstalledPackages(0)
                val userId = android.os.Process.myUserHandle().hashCode()

                packages.mapNotNull { pi ->
                    val ai = pi.applicationInfo ?: return@mapNotNull null
                    if (pi.packageName == context.packageName) return@mapNotNull null // hide ourselves

                    val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                    LocalAppItem(
                        packageName = pi.packageName,
                        name = pm.getApplicationLabel(ai).toString(),
                        version = pi.versionName ?: "",
                        isSystem = isSystem,
                        userId = userId
                    )
                }
            }
            allApps.clear()
            allApps.addAll(apps)
            updateList()
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        updateList()
    }

    private fun updateList() {
        val query = _uiState.value.searchQuery.lowercase()
        val filtered = if (query.isBlank()) allApps else allApps.filter { it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query) }

        val systemApps = filtered.filter { it.isSystem }.sortedBy { it.name }
        val thirdPartyApps = filtered.filter { !it.isSystem }.sortedBy { it.name }

        _uiState.update { it.copy(isLoading = false, systemApps = systemApps, thirdPartyApps = thirdPartyApps) }
    }

    fun selectApp(app: LocalAppItem?) {
        _uiState.update { it.copy(selectedApp = app, permissions = emptyList()) }
        if (app != null) {
            loadPermissions(app)
        }
    }

    private fun loadPermissions(app: LocalAppItem) {
        _uiState.update { it.copy(isPermissionsLoading = true) }
        viewModelScope.launch {
            try {
                val perms = permissionManager.getAppPermissions(app.packageName, app.userId)
                // Сортировка: сначала обычные (isAppOp = false), потом AppOp
                val sorted = perms.sortedBy { it.isAppOp }
                _uiState.update { it.copy(permissions = sorted, isPermissionsLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isPermissionsLoading = false) }
            }
        }
    }

    fun togglePermission(entry: PermissionEntry, enable: Boolean) {
        val app = _uiState.value.selectedApp ?: return
        viewModelScope.launch {
            try {
                permissionManager.togglePermission(app.packageName, entry, enable, app.userId)
                // Обновляем список локально, чтобы UI реагировал сразу
                _uiState.update { state ->
                    val newPerms = state.permissions.map {
                        if (it.name == entry.name) it.copy(isGranted = enable) else it
                    }
                    state.copy(permissions = newPerms)
                }
            } catch (e: Exception) {
                // Обработка ошибки
            }
        }
    }
}
