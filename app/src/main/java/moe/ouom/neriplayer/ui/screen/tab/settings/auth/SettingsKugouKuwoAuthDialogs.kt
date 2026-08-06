package moe.ouom.neriplayer.ui.screen.tab.settings.auth

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 */

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.auth.KugouKuwoWebLoginActivity
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.viewmodel.auth.KugouAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.KuwoAuthViewModel

/**
 * 酷狗/酷我账号登录: cookie 粘贴导入(在 www.kugou.com / www.kuwo.cn
 * 登录后复制 Cookie 粘贴即可)。
 */
@Composable
internal fun SettingsKugouAuthDialogs(
    showSheet: Boolean,
    onDismissSheet: () -> Unit,
    inlineMsg: String?,
    onInlineMsgChange: (String?) -> Unit,
    vm: KugouAuthViewModel,
    showSavedCookieDialog: Boolean = false,
    onDismissSavedCookieDialog: () -> Unit = {},
    onOpenSheet: () -> Unit = {},
    onLogout: (() -> Unit)? = null
) {
    val composeResources = LocalResources.current
    val context = LocalContext.current
    val webLoginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val json = result.data?.getStringExtra(KugouKuwoWebLoginActivity.RESULT_COOKIE) ?: "{}"
            vm.importCookiesFromMap(vm.parseJsonToMap(json))
        } else {
            onInlineMsgChange(composeResources.getString(R.string.settings_cookie_cancelled))
        }
    }
    val launchWebLogin: () -> Unit = {
        onInlineMsgChange(null)
        AppContainer.pauseYouTubeBackgroundWebWorkForForegroundLogin()
        webLoginLauncher.launch(
            Intent(context, KugouKuwoWebLoginActivity::class.java)
                .putExtra(KugouKuwoWebLoginActivity.EXTRA_PLATFORM, KugouKuwoWebLoginActivity.PLATFORM_KUGOU)
        )
    }

    if (showSavedCookieDialog) {
        SavedCookieActionDialog(
            title = stringResource(R.string.settings_kugou_saved_cookie_title),
            message = stringResource(R.string.settings_kugou_saved_cookie_message),
            onDismiss = onDismissSavedCookieDialog,
            onContinueLogin = {
                onDismissSavedCookieDialog()
                onOpenSheet()
            },
            onLogout = {
                onDismissSavedCookieDialog()
                onLogout?.invoke()
            }
        )
    }

    if (showSheet) {
        SettingsCookieLoginSheet(
            title = stringResource(R.string.platform_kugou),
            initialTab = 1,
            inlineMsg = inlineMsg,
            onInlineMsgChange = onInlineMsgChange,
            onDismiss = onDismissSheet,
            browserTabLabel = stringResource(R.string.login_browser),
            browserButtonLabel = stringResource(R.string.login_open_kugou),
            browserHintContent = {
                androidx.compose.material3.Text(
                    stringResource(R.string.settings_kugou_login_hint),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            cookieLabel = stringResource(R.string.login_paste_kugou_cookie_hint),
            onBrowserLogin = launchWebLogin,
            onSaveCookie = { rawCookie ->
                if (rawCookie.isBlank()) {
                    onInlineMsgChange(composeResources.getString(R.string.auth_cookie_empty))
                } else {
                    vm.importCookiesFromRaw(rawCookie)
                }
            }
        )
    }
}

@Composable
internal fun SettingsKuwoAuthDialogs(
    showSheet: Boolean,
    onDismissSheet: () -> Unit,
    inlineMsg: String?,
    onInlineMsgChange: (String?) -> Unit,
    vm: KuwoAuthViewModel,
    showSavedCookieDialog: Boolean = false,
    onDismissSavedCookieDialog: () -> Unit = {},
    onOpenSheet: () -> Unit = {},
    onLogout: (() -> Unit)? = null
) {
    val composeResources = LocalResources.current
    val context = LocalContext.current
    val webLoginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val json = result.data?.getStringExtra(KugouKuwoWebLoginActivity.RESULT_COOKIE) ?: "{}"
            vm.importCookiesFromMap(vm.parseJsonToMap(json))
        } else {
            onInlineMsgChange(composeResources.getString(R.string.settings_cookie_cancelled))
        }
    }
    val launchWebLogin: () -> Unit = {
        onInlineMsgChange(null)
        AppContainer.pauseYouTubeBackgroundWebWorkForForegroundLogin()
        webLoginLauncher.launch(
            Intent(context, KugouKuwoWebLoginActivity::class.java)
                .putExtra(KugouKuwoWebLoginActivity.EXTRA_PLATFORM, KugouKuwoWebLoginActivity.PLATFORM_KUWO)
        )
    }

    if (showSavedCookieDialog) {
        SavedCookieActionDialog(
            title = stringResource(R.string.settings_kuwo_saved_cookie_title),
            message = stringResource(R.string.settings_kuwo_saved_cookie_message),
            onDismiss = onDismissSavedCookieDialog,
            onContinueLogin = {
                onDismissSavedCookieDialog()
                onOpenSheet()
            },
            onLogout = {
                onDismissSavedCookieDialog()
                onLogout?.invoke()
            }
        )
    }

    if (showSheet) {
        SettingsCookieLoginSheet(
            title = stringResource(R.string.platform_kuwo),
            initialTab = 1,
            inlineMsg = inlineMsg,
            onInlineMsgChange = onInlineMsgChange,
            onDismiss = onDismissSheet,
            browserTabLabel = stringResource(R.string.login_browser),
            browserButtonLabel = stringResource(R.string.login_open_kuwo),
            browserHintContent = {
                androidx.compose.material3.Text(
                    stringResource(R.string.settings_kuwo_login_hint),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            cookieLabel = stringResource(R.string.login_paste_kuwo_cookie_hint),
            onBrowserLogin = launchWebLogin,
            onSaveCookie = { rawCookie ->
                if (rawCookie.isBlank()) {
                    onInlineMsgChange(composeResources.getString(R.string.auth_cookie_empty))
                } else {
                    vm.importCookiesFromRaw(rawCookie)
                }
            }
        )
    }
}
