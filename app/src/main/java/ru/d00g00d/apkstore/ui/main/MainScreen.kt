package ru.d00g00d.apkstore.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import ru.d00g00d.apkstore.data.model.AppModel
import ru.d00g00d.apkstore.data.preferences.ThemeMode
import ru.d00g00d.apkstore.ui.theme.ThemeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = koinViewModel(),
    themeViewModel: ThemeViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val themeMode by themeViewModel.themeMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Поиск...") },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Очистить")
                                }
                            }
                        },
                        singleLine = true
                    )
                },
                actions = {
                    IconButton(onClick = { themeViewModel.toggleTheme() }) {
                        val icon = when (themeMode) {
                            ThemeMode.LIGHT -> Icons.Filled.LightMode
                            ThemeMode.DARK -> Icons.Filled.DarkMode
                            ThemeMode.AUTO -> Icons.Filled.BrightnessAuto
                        }
                        Icon(icon, contentDescription = "Переключить тему")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.error != null) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(text = "Ошибка: ${uiState.error}", color = MaterialTheme.colorScheme.error)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = "Доступные приложения",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                items(uiState.apps) { itemState ->
                    AppItem(
                        itemState = itemState,
                        onDownloadClick = { viewModel.downloadApp(itemState) },
                        onOpenClick = { viewModel.openApp(itemState.app.packageName) }
                    )
                }
            }
        }
    }
}

@Composable
fun AppItem(
    itemState: AppItemState,
    onDownloadClick: () -> Unit,
    onOpenClick: () -> Unit
) {
    val app = itemState.app
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(80.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("ICON")
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = app.version,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            when (itemState.status) {
                AppStatus.DOWNLOAD, AppStatus.UPDATE -> {
                    Button(
                        onClick = onDownloadClick,
                        modifier = Modifier.size(width = 140.dp, height = 64.dp)
                    ) {
                        val text = if (itemState.status == AppStatus.UPDATE) "Обновить" else "Скачать"
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                AppStatus.DOWNLOADING -> {
                    Box(modifier = Modifier.size(width = 140.dp, height = 64.dp), contentAlignment = Alignment.Center) {
                        if (itemState.progress >= 0) {
                            LinearProgressIndicator(
                                progress = { itemState.progress / 100f },
                                modifier = Modifier.fillMaxWidth().height(24.dp)
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
                AppStatus.OPEN -> {
                    OutlinedButton(
                        onClick = onOpenClick,
                        modifier = Modifier.size(width = 140.dp, height = 64.dp)
                    ) {
                        Text("Открыть", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
