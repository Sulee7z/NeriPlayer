package moe.ouom.neriplayer.ui.screen.tab.library

import android.app.Application
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomMusicLibraryContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun officialPlaylistRowsKeepClicksAndMetadataInBothThemes() {
        val style = mutableStateOf(UiStyle.Clean)
        val playlist = PlaylistSummary(42L, "A playlist", "", 1234, 12, source = "qq")
        var opened: PlaylistSummary? = null
        compose.setContent {
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "885522", uiStyle = style.value, animateColors = false) {
                Surface {
                    CustomMusicLibraryContent(listOf(playlist), rememberLazyListState(), false, null,
                        false, {}, { opened = it })
                }
            }
        }
        for (theme in listOf(UiStyle.Clean, UiStyle.Material)) {
            compose.runOnIdle { style.value = theme; opened = null }
            compose.onNodeWithText("A playlist").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(playlist, opened) }
        }
    }

    @Test fun failedLibraryHasReachableRetry() {
        var retries = 0
        compose.setContent {
            Surface {
                CustomMusicLibraryContent(emptyList(), rememberLazyListState(), false, "Connection failed",
                    false, { retries++ }, {})
            }
        }
        compose.onNodeWithText("Connection failed").assertIsDisplayed()
        compose.onNodeWithText("刷新").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
