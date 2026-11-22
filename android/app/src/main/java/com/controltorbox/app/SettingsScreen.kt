package com.controltorbox.app

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(vm: MainViewModel, state: UiState) {
    val c = C.c
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = vm.store
    var settings by remember { mutableStateOf(store.alertSettings) }
    var tokenDialog by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }

    fun update(s: AlertSettings) { settings = s; vm.saveAlertSettings(s) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Ajustes", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.padding(vertical = 8.dp))

        Column {
            SettingsGroupTitle("Cuenta de TorBox")
            AppCard(modifier = Modifier.fillMaxWidth()) { Column {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Ic.Check, c.green, c.greenSoft, size = 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text("Conectado", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                        Text("Token ••••••••${store.token.takeLast(4)}", fontSize = 12.sp, color = c.muted)
                    }
                    PillButton("Cambiar", { tokenDialog = true }, filled = false, height = 36.dp)
                }
                HorizontalDivider(color = c.chip, modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Almacenamiento de la cuenta", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.text)
                        Text(
                            when {
                                state.accountLimitFromTorbox -> "Leído de TorBox" + (state.planName?.let { " · plan $it" } ?: "")
                                else -> (state.planName?.let { "Plan $it · " } ?: "") + "TorBox no lo indica: ponlo tú"
                            },
                            fontSize = 12.sp, color = c.muted,
                        )
                    }
                    if (state.accountLimitFromTorbox) {
                        Text(Format.size(state.accountLimit ?: 0), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    } else {
                        Stepper(
                            value = vm.store.accountLimitManualGb, enabled = true, min = 0, steps = listOf(100, 500),
                            format = { if (it == 0) "—" else Format.size(it * UiState.GB) },
                            onChange = vm::setAccountLimitGb,
                        )
                    }
                }
            } }
        }

        Column {
            SettingsGroupTitle("Avisos · una vez al día")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    SwitchRow("Nuevo sin español", "Cuando añadas algo que no lo indica", settings.notifyNoSpanish) { update(settings.copy(notifyNoSpanish = it)) }
                    HorizontalDivider(color = c.chip)
                    SwitchRow("Nuevo duplicado", "Cuando añadas algo que ya tenías", settings.notifyDuplicates) { update(settings.copy(notifyDuplicates = it)) }
                    HorizontalDivider(color = c.chip)
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Espacio por encima de", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.text)
                            Text(
                                if (settings.notifyStorage) "Ahora ocupas ${Format.size(state.totalSize)}" else "Aviso desactivado",
                                fontSize = 12.sp, color = c.muted,
                            )
                        }
                        Stepper(
                            value = settings.storageLimitGb, enabled = settings.notifyStorage,
                            onChange = { update(settings.copy(storageLimitGb = it)) },
                        )
                    }
                    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Avisar del espacio", fontSize = 13.sp, color = c.chipText, modifier = Modifier.weight(1f))
                        Switch(settings.notifyStorage, { update(settings.copy(notifyStorage = it)) }, colors = switchColors())
                    }
                    HorizontalDivider(color = c.chip)
                    SwitchRow("Nueva versión de la app", "Te avisa y se instala con un toque", settings.notifyUpdates) { update(settings.copy(notifyUpdates = it)) }
                }
            }
            Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val last = store.lastCheck
                Text(
                    if (last > 0) "Última comprobación: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))
                    else "Aún no se ha comprobado",
                    fontSize = 12.sp, color = c.muted, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { MonitorWorker.runNow(context); updateMsg = "Comprobando… te llegará un aviso si hay novedades." }) {
                    Text("Comprobar ahora", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (!Notifications.canNotify(context)) {
                Text(
                    "Las notificaciones están desactivadas para Controltorbox en los ajustes de Android.",
                    fontSize = 12.sp, color = c.orange, modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        Column {
            SettingsGroupTitle("Aplicación")
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Versión ${BuildConfig.VERSION_NAME}", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.text)
                        val u = state.update
                        Text(
                            when {
                                u != null -> "Hay una versión nueva: ${u.versionName}"
                                updateMsg != null -> updateMsg!!
                                else -> "Se comprueba una vez al día"
                            },
                            fontSize = 12.sp, color = if (u != null) c.accent else c.muted, fontWeight = if (u != null) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    if (state.update != null) {
                        PillButton(
                            state.updateProgress?.let { "${(it * 100).toInt()} %" } ?: "Instalar", vm::installUpdate,
                            enabled = state.updateProgress == null, height = 36.dp,
                        )
                    } else {
                        PillButton(
                            if (checking) "Buscando…" else "Buscar", filled = false, enabled = !checking, height = 36.dp,
                            onClick = { checking = true; scope.launch { updateMsg = vm.checkForUpdate(); checking = false } },
                        )
                    }
                }
            }
            Text(
                "Android puede retrasar la comprobación diaria para ahorrar batería. Si no te llegan avisos, quita la optimización de batería para Controltorbox.",
                fontSize = 12.sp, lineHeight = 17.sp, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        }
    }

    if (tokenDialog) TokenDialog(vm, onDismiss = { tokenDialog = false })
}

@Composable
private fun switchColors() = SwitchDefaults.colors(checkedTrackColor = C.c.accent, checkedThumbColor = C.c.onAccent)

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = C.c
    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.text)
            Text(subtitle, fontSize = 12.sp, color = c.muted)
        }
        Switch(checked, onChange, colors = switchColors())
    }
}

@Composable
private fun Stepper(
    value: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    min: Int = 50,
    steps: List<Int> = listOf(50, 250),
    format: (Int) -> String = { "$it GB" },
) {
    val c = C.c
    // Pasos pequeños hasta 1 TB y más grandes por encima.
    val up = if (value >= 1000) steps[1] else steps[0]
    val down = if (value > 1000) steps[1] else steps[0]
    Row(
        Modifier.border(1.dp, c.borderStrong, RoundedCornerShape(20.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onChange((value - down).coerceAtLeast(min)) }, enabled = enabled && value > min, modifier = Modifier.size(40.dp)) {
            Text("−", fontSize = 18.sp, color = if (enabled) c.text else c.faint)
        }
        Text(
            format(value), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) c.text else c.faint,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 72.dp),
        )
        IconButton(onClick = { onChange(value + up) }, enabled = enabled, modifier = Modifier.size(40.dp)) {
            Text("+", fontSize = 18.sp, color = if (enabled) c.text else c.faint)
        }
    }
}

@Composable
private fun TokenDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = C.c
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cambiar token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = token, onValueChange = { token = it; error = null },
                    label = { Text("Nuevo API token") }, singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Ocultar" else "Ver") } },
                )
                error?.let { Text(it, color = c.orange, fontSize = 13.sp) }
                TextButton(onClick = { confirmLogout = true }) { Text("Desconectar esta cuenta", color = c.orange) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = token.isNotBlank() && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        try { vm.saveToken(token); onDismiss() } catch (e: TorboxException) { error = "No es válido: ${e.message}" } finally { saving = false }
                    }
                },
            ) { Text(if (saving) "Comprobando…" else "Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("¿Desconectar?") },
            text = { Text("Se borrará el token de este móvil. Tus archivos de TorBox no se tocan.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; vm.clearToken(); onDismiss() }) { Text("Desconectar", color = c.orange) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancelar") } },
        )
    }
}
