package ru.d00g00d.apkstore.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.d00g00d.apkstore.data.preferences.ThemeMode
import ru.d00g00d.apkstore.data.preferences.ThemePreferences

class ThemeViewModel(private val preferences: ThemePreferences) : ViewModel() {
    val themeMode = preferences.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ThemeMode.AUTO
    )

    fun toggleTheme() {
        val next = when (themeMode.value) {
            ThemeMode.AUTO -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.AUTO
        }
        viewModelScope.launch {
            preferences.setThemeMode(next)
        }
    }
}
