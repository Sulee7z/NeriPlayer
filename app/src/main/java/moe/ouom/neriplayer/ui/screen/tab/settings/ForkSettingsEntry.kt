package moe.ouom.neriplayer.ui.screen.tab.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountCardSurface
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.common.R
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(Triple("qq", R.string.platform_qqmusic, qqState.hasSavedCookies),
            Triple("kugou", R.string.platform_kugou, kugouState.hasSavedCookies),
            Triple("kuwo", R.string.platform_kuwo, kuwoState.hasSavedCookies)).forEach { (id, title, signedIn) ->
            SettingsAccountCardSurface(onClick = { page = id; message = null }) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (id == "qq") Icon(painterResource(moe.ouom.neriplayer.R.drawable.ic_qq_music),
                            contentDescription = null, modifier = Modifier.size(36.dp))
                        else Icon(Icons.Outlined.LibraryMusic, contentDescription = null,
                            modifier = Modifier.size(36.dp))
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                    }
                    MiuixSettingsOutlinedButton(onClick = {
                        if (signedIn) {
                            when (id) { "qq" -> qq.clearCookies(); "kugou" -> kugou.clearCookies(); else -> kuwo.clearCookies() }
                        } else { page = id; message = null }
                    }) { Text(stringResource(when {
                        signedIn -> R.string.settings_saved_cookie_logout
                        id == "qq" -> R.string.login_qr
                        else -> R.string.login_browser
                    })) }
                }
            }
        }
    }
    SettingsQqAuthDialogs(page == "qq", 0, { page = null }, message, { message = it }, qq)
    SettingsKugouAuthDialogs(page == "kugou", { page = null }, message, { message = it }, kugou)
    SettingsKuwoAuthDialogs(page == "kuwo", { page = null }, message, { message = it }, kuwo)
}
