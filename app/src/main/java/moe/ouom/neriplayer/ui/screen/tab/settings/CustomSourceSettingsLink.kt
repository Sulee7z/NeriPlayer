package moe.ouom.neriplayer.ui.screen.tab.settings

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.common.R

/** Shared by the playback-source page and the backwards-compatible footer. */
@Composable
internal fun CustomSourceSettingsLink(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.custom_source_entry_title)) },
        supportingContent = { Text(stringResource(R.string.custom_source_entry_desc)) },
        leadingContent = { Icon(Icons.Outlined.LibraryMusic, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("settings-custom-source-entry").clickable(onClick = onClick)
    )
}
