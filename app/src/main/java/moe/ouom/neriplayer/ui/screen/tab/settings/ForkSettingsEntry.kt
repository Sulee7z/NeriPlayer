package moe.ouom.neriplayer.ui.screen.tab.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.screen.customsource.CustomSourceScreen
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsQqAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsKugouAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsKuwoAuthDialogs
import moe.ouom.neriplayer.ui.viewmodel.auth.QqAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.QqAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.KugouAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.KuwoAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.KugouKuwoAuthEvent

@Composable
internal fun ForkSettingsEntry() {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val qq: QqAuthViewModel = viewModel()
    val kugou: KugouAuthViewModel = viewModel()
    val kuwo: KuwoAuthViewModel = viewModel()
    val qqState by qq.uiState.collectAsStateWithLifecycle()
    val kugouState by kugou.uiState.collectAsStateWithLifecycle()
    val kuwoState by kuwo.uiState.collectAsStateWithLifecycle()
    val settings = AppContainer.settingsRepo
    val bypass by settings.customSourceBypassProxyFlow.collectAsStateWithLifecycle(true)
    val alwaysProxy by settings.customSourceAlwaysProxyFlow.collectAsStateWithLifecycle(false)
    val scope = rememberCoroutineScope()
    LaunchedEffect(qq) { qq.events.collect {
        when (it) {
            QqAuthEvent.LoginSuccess -> { page = null; message = null }
            is QqAuthEvent.ShowSnack -> message = it.message
        }
    } }
    LaunchedEffect(kugou) { kugou.events.collect {
        when (it) {
            KugouKuwoAuthEvent.LoginSuccess -> { page = null; message = null }
            is KugouKuwoAuthEvent.ShowSnack -> message = it.message
        }
    } }
    LaunchedEffect(kuwo) { kuwo.events.collect {
        when (it) {
            KugouKuwoAuthEvent.LoginSuccess -> { page = null; message = null }
            is KugouKuwoAuthEvent.ShowSnack -> message = it.message
        }
    } }
    Column {
        ListItem(headlineContent = { Text(stringResource(R.string.custom_source_title)) },
            modifier = Modifier.clickable { page = "source" })
        ListItem(headlineContent = { Text(stringResource(R.string.settings_custom_source_bypass_proxy)) },
            trailingContent = { Switch(bypass, { scope.launch { settings.setCustomSourceBypassProxy(it) } }) })
        ListItem(headlineContent = { Text(stringResource(R.string.settings_custom_source_always_proxy)) },
            trailingContent = { Switch(alwaysProxy, { scope.launch { settings.setCustomSourceAlwaysProxy(it) } }) })
        listOf(Triple("qq", R.string.platform_qqmusic, qqState.hasSavedCookies),
            Triple("kugou", R.string.platform_kugou, kugouState.hasSavedCookies),
            Triple("kuwo", R.string.platform_kuwo, kuwoState.hasSavedCookies)).forEach { (id, title, signedIn) ->
            ListItem(headlineContent = { Text(stringResource(title)) },
                modifier = Modifier.clickable { page = id; message = null },
                trailingContent = {
                    if (signedIn) TextButton(onClick = {
                        when (id) { "qq" -> qq.clearCookies(); "kugou" -> kugou.clearCookies(); else -> kuwo.clearCookies() }
                    }) { Text(stringResource(R.string.settings_saved_cookie_logout)) }
                })
        }
    }
    SettingsQqAuthDialogs(page == "qq", 0, { page = null }, message, { message = it }, qq)
    SettingsKugouAuthDialogs(page == "kugou", { page = null }, message, { message = it }, kugou)
    SettingsKuwoAuthDialogs(page == "kuwo", { page = null }, message, { message = it }, kuwo)
    if (page == "source") Dialog(onDismissRequest = { page = null },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { CustomSourceScreen(onBack = { page = null }) }
    }
}
