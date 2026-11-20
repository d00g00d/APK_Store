package ru.d00g00d.apkstore.ui.local

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import ru.d00g00d.apkstore.data.preferences.ThemeMode
import ru.d00g00d.apkstore.ui.components.AccordionGroup
import ru.d00g00d.apkstore.ui.theme.ThemeViewModel
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.BrightnessAuto
import ru.d00g00d.apkstore.core.display.DisplayController
import androidx.compose.animation.AnimatedVisibility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAppsScreen(
    viewModel: LocalAppsViewModel = koinViewModel(),
    themeViewModel: ThemeViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val themeMode by themeViewModel.themeMode.collectAsState()

    var showThirdParty by remember { mutableStateOf(true) }
    var showSystem by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                        placeholder = { Text("Поиск локальных...") },
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
            // Левая панель (60%) - Список приложений
            Box(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2), // 2 столбца в сетке
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(span = { GridItemSpan(2) }) {
                            AccordionGroup(
                                title = "Сторонние приложения (${uiState.thirdPartyApps.size})",
                                isExpanded = showThirdParty,
                                onToggle = { showThirdParty = !showThirdParty }
                            ) {}
                        }
                        if (showThirdParty) {
                            items(uiState.thirdPartyApps) { app ->
                                AppGridItem(app, isSelected = uiState.selectedApp?.packageName == app.packageName) {
                                    viewModel.selectApp(app)
                                }
                            }
                        }

                        item(span = { GridItemSpan(2) }) {
                            Spacer(modifier = Modifier.height(16.dp))
                            AccordionGroup(
                                title = "Системные приложения (${uiState.systemApps.size})",
                                isExpanded = showSystem,
                                onToggle = { showSystem = !showSystem }
                            ) {}
                        }
                        if (showSystem) {
                            items(uiState.systemApps) { app ->
                                AppGridItem(app, isSelected = uiState.selectedApp?.packageName == app.packageName) {
                                    viewModel.selectApp(app)
                                }
                            }
                        }
                    }
                }
            }

            // Вертикальный разделитель
            VerticalDivider(modifier = Modifier.fillMaxHeight())

            // Правая панель (40%) - Свойства и Права
            Box(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                val app = uiState.selectedApp
                if (app == null) {
                    Text("Выберите приложение из списка", modifier = Modifier.align(Alignment.Center))
                } else {
                    var showUninstallDialog by remember { mutableStateOf(false) }
                    var showAdvancedLaunch by remember { mutableStateOf(false) }
                    
                    var selectedDisplay by remember { mutableStateOf<Int?>(null) }
                    var selectedUser by remember { mutableStateOf<Int?>(null) }
                    var enableFullscreen by remember { mutableStateOf(false) }

                    if (showUninstallDialog) {
                        AlertDialog(
                            onDismissRequest = { showUninstallDialog = false },
                            title = { Text("Удаление приложения") },
                            text = { Text("Вы уверены, что хотите удалить ${app.name}?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showUninstallDialog = false
                                    viewModel.uninstallApp(app)
                                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showUninstallDialog = false }) { Text("Отмена") }
                            }
                        )
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.name, style = MaterialTheme.typography.headlineMedium)
                                Text(app.packageName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Версия: ${app.version}", style = MaterialTheme.typography.bodySmall)
                            }
                            // Кнопки действий
                            IconButton(onClick = { viewModel.openApp(app.packageName, selectedDisplay, enableFullscreen, selectedUser ?: 0) }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Открыть")
                            }
                            IconButton(onClick = { viewModel.stopApp(app, selectedUser ?: 0) }) {
                                Icon(Icons.Filled.Stop, contentDescription = "Остановить")
                            }
                            IconButton(onClick = { showUninstallDialog = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Опции запуска (Edge панель - встраиваем как раскрывающуюся карту)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { showAdvancedLaunch = !showAdvancedLaunch },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Расширенный запуск (Дисплей)", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                                
                                AnimatedVisibility(visible = showAdvancedLaunch) {
                                    Column(modifier = Modifier.padding(top = 16.dp)) {
                                        if (uiState.users.isNotEmpty()) {
                                            Text("Пользователь (Экран):", style = MaterialTheme.typography.bodyMedium)
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                                uiState.users.forEach { user ->
                                                    FilterChip(
                                                        selected = (selectedUser == user.id) || (selectedUser == null && user.id == 0),
                                                        onClick = { selectedUser = user.id },
                                                        label = { Text(user.name) }
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }

                                        Text("Экран запуска (Display):", style = MaterialTheme.typography.bodyMedium)
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                            FilterChip(selected = selectedDisplay == null, onClick = { selectedDisplay = null }, label = { Text("Авто") })
                                            FilterChip(selected = selectedDisplay == DisplayController.DISPLAY_DRIVER_1001, onClick = { selectedDisplay = DisplayController.DISPLAY_DRIVER_1001 }, label = { Text("Водитель") })
                                            FilterChip(selected = selectedDisplay == DisplayController.DISPLAY_FULL_1003, onClick = { selectedDisplay = DisplayController.DISPLAY_FULL_1003 }, label = { Text("Пассажир/Full") })
                                        }
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Скрыть статус-бар (Immersive):", modifier = Modifier.weight(1f))
                                            Switch(checked = enableFullscreen, onCheckedChange = { enableFullscreen = it })
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Text("Настройки и Права", style = MaterialTheme.typography.titleLarge)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        if (uiState.isPermissionsLoading) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        } else if (uiState.permissions.isEmpty()) {
                            Text("Нет доступных прав для изменения")
                        } else {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                uiState.permissions.forEach { perm ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                            Text(perm.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(if (perm.isAppOp) "Специальное право" else "Манифест", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Switch(
                                            checked = perm.isGranted,
                                            onCheckedChange = { viewModel.togglePermission(perm, it) }
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
}

@Composable
fun AppGridItem(app: LocalAppItem, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(contentAlignment = Alignment.Center) { Text("IC") }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(app.version, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}