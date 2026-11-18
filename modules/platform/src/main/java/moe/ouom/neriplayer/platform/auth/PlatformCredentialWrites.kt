package moe.ouom.neriplayer.platform.auth

import android.content.SharedPreferences
import moe.ouom.neriplayer.common.storage.SecurePreferencesOpener
import java.io.IOException

internal fun SharedPreferences.commitPlatformMutation(mutate: SharedPreferences.Editor.() -> Unit) {
    if (!SecurePreferencesOpener.isPersistent(this)) throw IOException("账号加密存储暂不可用，未保存凭据")
    val editor = edit()
    editor.mutate()
    if (!editor.commit()) throw IOException("账号凭据写入失败")
}

internal fun sanitizePlatformCookies(cookies: Map<String, String>): Map<String, String> =
    cookies.filter { (name, value) ->
        name.matches(Regex("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")) && value.isNotBlank() && value != "null" &&
            value.none { it.code < 32 || it.code == 127 || it == ';' }
    }
