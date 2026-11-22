package com.controltorbox.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val nav = Nav()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.createChannel(this)
        MonitorWorker.schedule(this)
        if (vm.store.token.isNotBlank()) askNotificationPermission()
        handleIntent(intent)
        setContent {
            AppTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { vm.refresh() }
                AppRoot(vm, state, nav, onTokenSaved = ::askNotificationPermission)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        vm.refresh()
    }

    /** Al tocar una notificación se abre directamente la pantalla que corresponde. */
    private fun handleIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_FILTER)) {
            FILTER_NO_ES -> { nav.goTab(Tab.HOME); nav.open(Route.Review) }
            FILTER_DUPS -> { nav.goTab(Tab.HOME); nav.open(Route.Dups) }
            SHOW_UPDATE -> { nav.goTab(Tab.HOME); vm.reloadUpdate() }
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
