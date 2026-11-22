package com.controltorbox.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

enum class Tab(val label: String) { HOME("Inicio"), LIBRARY("Biblioteca"), SETTINGS("Ajustes") }

/** Pantallas que se abren encima de las pestañas. */
sealed interface Route {
    data object Review : Route
    data object Dups : Route
    data class Detail(val key: String) : Route
}

/** Navegación sencilla: pestaña actual + pila de pantallas abiertas encima. */
class Nav(initialTab: Tab = Tab.HOME) {
    var tab by mutableStateOf(initialTab)
    val stack = mutableStateListOf<Route>()
    val top: Route? get() = stack.lastOrNull()
    fun open(r: Route) { stack += r }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun goTab(t: Tab) { stack.clear(); tab = t }
}

@Composable
fun AppRoot(vm: MainViewModel, state: UiState, nav: Nav = remember { Nav() }, onTokenSaved: () -> Unit = {}) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    if (!state.hasToken) {
        WelcomeScreen(vm, onTokenSaved)
        return
    }

    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.back() }
    BackHandler(enabled = nav.stack.isEmpty() && nav.tab != Tab.HOME) { nav.goTab(Tab.HOME) }

    val top = nav.top
    Scaffold(
        containerColor = C.c.bg,
        // Cada pantalla gestiona la barra de estado; la de navegación la gestiona la barra inferior.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (top == null) {
                Column {
                    if (state.showUpdateDock) UpdateDock(state, onInstall = vm::installUpdate, onClose = vm::dismissUpdate)
                    BottomNav(nav)
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(C.c.bg)) {
            when (top) {
                null -> when (nav.tab) {
                    Tab.HOME -> HomeScreen(vm, state, nav)
                    Tab.LIBRARY -> LibraryScreen(vm, state, nav)
                    Tab.SETTINGS -> SettingsScreen(vm, state)
                }
                Route.Review -> ReviewScreen(vm, state, nav)
                Route.Dups -> DuplicatesScreen(vm, state, nav)
                is Route.Detail -> {
                    val row = state.row(top.key)
                    if (row == null) LaunchedEffect(top) { nav.back() } else DetailScreen(vm, row, nav)
                }
            }
            if (state.loading || state.analyzing != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    state.error?.let { err ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            title = { Text("Aviso") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = vm::clearMessage) { Text("Aceptar") } },
        )
    }
}

@Composable
private fun BottomNav(nav: Nav) {
    val c = C.c
    Column {
        HorizontalDivider(color = c.border)
        NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
            Tab.entries.forEach { t ->
                val icon = when (t) { Tab.HOME -> Ic.Home; Tab.LIBRARY -> Ic.Library; Tab.SETTINGS -> Ic.Settings }
                NavigationBarItem(
                    selected = nav.tab == t,
                    onClick = { nav.goTab(t) },
                    icon = { Icon(icon, null, modifier = Modifier.size(22.dp)) },
                    label = { Text(t.label, fontSize = 12.sp, fontWeight = if (nav.tab == t) FontWeight.SemiBold else FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.accentDark, selectedTextColor = c.accentDark, indicatorColor = c.accentSoft,
                        unselectedIconColor = c.chipText, unselectedTextColor = c.chipText,
                    ),
                )
            }
        }
    }
}

/** Aviso de nueva versión, pegado encima de la barra de navegación. */
@Composable
fun UpdateDock(state: UiState, onInstall: () -> Unit, onClose: () -> Unit) {
    val c = C.c
    val info = state.update ?: return
    Surface(
        color = c.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        modifier = Modifier.fillMaxWidth().shadow(16.dp, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp).size(width = 36.dp, height = 4.dp).background(c.border, RoundedCornerShape(2.dp)))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(Ic.Download, c.accent, c.accentSoft)
                Column(Modifier.weight(1f)) {
                    Text("Nueva versión ${info.versionName}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    Text(
                        state.updateProgress?.let { "Descargando… ${(it * 100).toInt()} %" } ?: "Se instala encima, sin perder nada",
                        fontSize = 12.sp, color = c.muted,
                    )
                }
                PillButton("Instalar", onInstall, enabled = state.updateProgress == null, height = 40.dp)
                IconButton(onClick = onClose) { Icon(Ic.Close, "Ahora no", tint = c.muted, modifier = Modifier.size(20.dp)) }
            }
            state.updateProgress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 8.dp))
            }
        }
    }
}

/** Primera vez: pedir el token. */
@Composable
fun WelcomeScreen(vm: MainViewModel, onTokenSaved: () -> Unit) {
    val c = C.c
    val scope = rememberCoroutineScope()
    var token by rememberSaveable { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        IconTile(Ic.Library, c.accent, c.accentSoft, size = 56.dp)
        Text("Controltorbox", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.text)
        Text(
            "Encuentra lo que no tiene audio en español y los duplicados de tu TorBox, y libera espacio.",
            fontSize = 15.sp, lineHeight = 22.sp, color = c.muted,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = token, onValueChange = { token = it; error = null },
            label = { Text("API token de TorBox") }, singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Ocultar" else "Ver") } },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Lo encontrarás en torbox.app → Settings → API Key. Se guarda solo en este móvil.", fontSize = 12.sp, color = c.muted)
        error?.let { Text(it, color = c.orange, fontSize = 13.sp) }
        PillButton(
            if (saving) "Comprobando…" else "Conectar", height = 52.dp, enabled = token.isNotBlank() && !saving,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                saving = true
                scope.launch {
                    try {
                        vm.saveToken(token); onTokenSaved()
                    } catch (e: TorboxException) {
                        error = "El token no es válido: ${e.message}"
                    } finally {
                        saving = false
                    }
                }
            },
        )
    }
}
