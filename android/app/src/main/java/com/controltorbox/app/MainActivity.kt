package com.controltorbox.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val pendingFilter = mutableStateOf<AudioFilter?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.createChannel(this)
        handleIntent(intent)
        MonitorWorker.schedule(this)
        if (vm.store.token.isNotBlank()) askNotificationPermission()
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(!state.hasToken) }
                LaunchedEffect(Unit) { vm.refresh() }
                LaunchedEffect(pendingFilter.value) {
                    pendingFilter.value?.let { vm.setFilter(it); pendingFilter.value = null; showSettings = false }
                }
                if (showSettings || !state.hasToken) {
                    BackHandler(enabled = state.hasToken) { showSettings = false }
                    SettingsScreen(
                        vm = vm,
                        canGoBack = state.hasToken,
                        onBack = { showSettings = false },
                        onTokenSaved = { showSettings = false; askNotificationPermission() },
                    )
                } else {
                    MainScreen(vm = vm, state = state, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        vm.refresh()
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_FILTER)) {
            FILTER_NO_ES -> pendingFilter.value = AudioFilter.NOT_ES
            FILTER_DUPS -> pendingFilter.value = AudioFilter.DUPS
            SHOW_UPDATE -> vm.reloadUpdate()
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canNotify(this)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_FILTER = "filter"
        const val FILTER_NO_ES = "no_es"
        const val FILTER_DUPS = "dups"
        const val SHOW_UPDATE = "update"
    }
}

val Green = Color(0xFF1E9E5A)
val Red = Color(0xFFD23B3B)
val Amber = Color(0xFFC97A10)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(primary = Color(0xFF8AB4F8), secondary = Color(0xFF8AB4F8))
    } else {
        lightColorScheme(primary = Color(0xFF2F6FDE), secondary = Color(0xFF2F6FDE))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
