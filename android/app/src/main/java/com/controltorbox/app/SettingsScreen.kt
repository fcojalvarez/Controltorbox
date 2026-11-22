package com.controltorbox.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val INTERVALS = listOf(15 to "Cada 15 min", 30 to "Cada 30 min", 60 to "Cada hora", 180 to "Cada 3 horas", 360 to "Cada 6 horas")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, canGoBack: Boolean, onBack: () -> Unit, onTokenSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = vm.store
    var tokenInput by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var tokenError by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(store.alertSettings) }
    var limitText by remember { mutableStateOf(settings.storageLimitGb.toString()) }
    val current = store.token

    fun update(s: AlertSettings) { settings = s; vm.saveAlertSettings(s) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    if (canGoBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("API token de TorBox", fontWeight = FontWeight.SemiBold)
            Text(
                if (current.isNotBlank()) "Token actual: ••••••••${current.takeLast(4)}" else "No hay ningún token configurado.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it; tokenError = null },
                label = { Text("Pega aquí tu API token") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Ocultar" else "Ver") } },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Lo encontrarás en torbox.app → Settings → API Key. Se guarda solo en este móvil.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tokenError?.let { Text(it, color = Red, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = tokenInput.isNotBlank() && !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                vm.saveToken(tokenInput)
                                tokenInput = ""
                                onTokenSaved()
                            } catch (e: TorboxException) {
                                tokenError = "El token no es válido: ${e.message}"
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) { Text(if (saving) "Comprobando…" else "Guardar") }
                if (current.isNotBlank()) OutlinedButton(onClick = { vm.clearToken() }) { Text("Borrar token") }
            }

            HorizontalDivider()
            Text("Notificaciones", fontWeight = FontWeight.SemiBold)
            Text(
                "La app comprueba tu TorBox en segundo plano y te avisa solo de lo nuevo.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!Notifications.canNotify(context)) {
                Text("Las notificaciones están desactivadas para esta app en los ajustes de Android.", color = Amber, fontSize = 13.sp)
            }
            SwitchRow("Nuevo elemento sin audio en español", settings.notifyNoSpanish) { update(settings.copy(notifyNoSpanish = it)) }
            SwitchRow("Nuevo duplicado (algo que ya tenías)", settings.notifyDuplicates) { update(settings.copy(notifyDuplicates = it)) }
            SwitchRow("Espacio ocupado por encima del límite", settings.notifyStorage) { update(settings.copy(notifyStorage = it)) }
            if (settings.notifyStorage) {
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { v ->
                        limitText = v.filter(Char::isDigit).take(6)
                        limitText.toIntOrNull()?.let { if (it > 0) update(settings.copy(storageLimitGb = it)) }
                    },
                    label = { Text("Límite (GB)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(160.dp),
                )
            }
            IntervalMenu(settings.intervalMinutes) { update(settings.copy(intervalMinutes = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = current.isNotBlank(), onClick = { MonitorWorker.runNow(context) }) { Text("Comprobar ahora") }
                Spacer(Modifier.width(12.dp))
                val last = store.lastCheck
                Text(
                    if (last > 0) "Última: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))
                    else "Aún no se ha comprobado",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Android puede retrasar las comprobaciones para ahorrar batería. Si no te llegan avisos, quita la " +
                    "optimización de batería para Controltorbox en los ajustes del móvil.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text("Versión ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IntervalMenu(minutes: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Comprobar", Modifier.weight(1f), fontSize = 14.sp)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(INTERVALS.firstOrNull { it.first == minutes }?.second ?: "Cada $minutes min")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                INTERVALS.forEach { (m, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onChange(m); open = false }) }
            }
        }
    }
}
