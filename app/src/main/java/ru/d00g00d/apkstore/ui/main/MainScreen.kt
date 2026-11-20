package ru.d00g00d.apkstore.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    
    // Хранение выбранного приложения для правой панели (паттерн Master-Detail)
    var selectedAppItem by remember { mutableStateOf<AppItemState?>(null) }
    // Обновляем выбранное приложение, если его статус изменился (скачалось/установилось)
    LaunchedEffect(uiState.apps) {
        selectedAppItem?.let { selected ->
            val updated = uiState.apps.find { it.app.id == selected.app.id }
            if (updated != null) {
                selectedAppItem = updated
            }
        }
    }

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
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Левая панель (60%) - Каталог
            Box(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (uiState.error != null) {
                    Text(text = "Ошибка: ${uiState.error}", color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.apps) { itemState ->
                            AppItem(
                                itemState = itemState,
                                isSelected = selectedAppItem?.app?.id == itemState.app.id,
                                onClick = { selectedAppItem = itemState }
                            )
                        }
                    }
                }
            }

            VerticalDivider(modifier = Modifier.fillMaxHeight())

            // Правая панель (40%) - Детали приложения
            Box(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                val currentItem = selectedAppItem
                if (currentItem == null) {
                    Text("Выберите приложение", modifier = Modifier.align(Alignment.Center))
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier = Modifier.size(100.dp).align(Alignment.CenterHorizontally),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.large
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("ICON")
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = currentItem.app.name,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Text(
                            text = currentItem.app.version,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Text(
                            text = currentItem.app.releaseNotes,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        // Блок кнопки управления
                        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                            when (currentItem.status) {
                                AppStatus.DOWNLOAD, AppStatus.UPDATE -> {
                                    Button(
                                        onClick = { viewModel.downloadApp(currentItem) },
                                        modifier = Modifier.fillMaxWidth().height(64.dp)
                                    ) {
                                        val text = if (currentItem.status == AppStatus.UPDATE) "Обновить" else "Скачать"
                                        Text(text, style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                                AppStatus.DOWNLOADING -> {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Скачивание...", style = MaterialTheme.typography.bodyMedium)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        if (currentItem.progress >= 0) {
                                            LinearProgressIndicator(
                                                progress = { currentItem.progress / 100f },
                                                modifier = Modifier.fillMaxWidth().height(16.dp)
                                            )
                                        } else {
                                            CircularProgressIndicator()
                                        }
                                    }
                                }
                                AppStatus.OPEN -> {
                                    OutlinedButton(
                                        onClick = { viewModel.openApp(currentItem.app.packageName) },
                                        modifier = Modifier.fillMaxWidth().height(64.dp)
                                    ) {
                                        Text("Открыть", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppItem(
    itemState: AppItemState,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val app = itemState.app
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("IC")
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Text(
                    text = app.version,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
