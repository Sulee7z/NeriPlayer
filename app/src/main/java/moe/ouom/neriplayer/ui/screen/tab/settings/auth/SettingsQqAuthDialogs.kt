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
 *
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.auth/SettingsQqAuthDialogs
 * Created: 2026/8/6
 */

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.auth.QqQrLoginActivity
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.viewmodel.auth.QqAuthViewModel

@Composable
internal fun SettingsQqAuthDialogs(
    showSheet: Boolean,
    initialTab: Int,
    onDismissSheet: () -> Unit,
    inlineMsg: String?,
    onInlineMsgChange: (String?) -> Unit,
    vm: QqAuthViewModel,
    showSavedCookieDialog: Boolean = false,
    onDismissSavedCookieDialog: () -> Unit = {},
    onOpenSheetAtTab: (Int) -> Unit = {},
    onLogout: (() -> Unit)? = null,
    onBrowserLogin: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current

    if (showSavedCookieDialog) {
        SavedCookieActionDialog(
            title = stringResource(R.string.settings_qq_saved_cookie_title),
            message = stringResource(R.string.settings_qq_saved_cookie_message),
            onDismiss = onDismissSavedCookieDialog,
            onContinueLogin = {
                onDismissSavedCookieDialog()
                onOpenSheetAtTab(0)
            },
            onLogout = {
                onDismissSavedCookieDialog()
                onLogout?.invoke()
            }
        )
    }

    if (showSheet) {
        val launchBrowserLogin: () -> Unit = onBrowserLogin?.let { injectedBrowserLogin ->
            {
                onInlineMsgChange(null)
                injectedBrowserLogin()
            }
        } ?: run {
            val qrLoginLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    val json = result.data?.getStringExtra(QqQrLoginActivity.RESULT_COOKIE) ?: "{}"
                    vm.importCookiesFromMap(vm.parseJsonToMap(json))
                } else {
                    onInlineMsgChange(composeResources.getString(R.string.settings_cookie_cancelled))
                }
            }
            val defaultBrowserLogin: () -> Unit = {
                onInlineMsgChange(null)
                AppContainer.pauseYouTubeBackgroundWebWorkForForegroundLogin()
                qrLoginLauncher.launch(Intent(context, QqQrLoginActivity::class.java))
            }
            defaultBrowserLogin
        }

        SettingsCookieLoginSheet(
            title = stringResource(R.string.platform_qqmusic),
            initialTab = initialTab,
            inlineMsg = inlineMsg,
            onInlineMsgChange = onInlineMsgChange,
            onDismiss = onDismissSheet,
            browserTabLabel = stringResource(R.string.login_qr),
            browserButtonLabel = stringResource(R.string.login_start_qq_qr),
            browserHintContent = {
                Text(
                    stringResource(R.string.settings_qq_login_browser_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            cookieLabel = stringResource(R.string.login_paste_qq_cookie_hint),
            onBrowserLogin = launchBrowserLogin,
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
