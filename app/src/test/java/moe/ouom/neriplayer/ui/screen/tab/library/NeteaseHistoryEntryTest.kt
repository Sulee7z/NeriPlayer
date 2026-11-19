package moe.ouom.neriplayer.ui.screen.tab.library

import android.app.Application
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NeteaseHistoryEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun historyRemainsVisibleAndClickableWithoutLoginInBothCategoriesAndThemes() {
        val category = mutableIntStateOf(NETEASE_CATEGORY_PLAYLIST)
        val style = mutableStateOf(UiStyle.Clean)
        var clicks = 0
        compose.setContent {
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "885522", uiStyle = style.value, animateColors = false) {
                Surface {
                    NeteaseLibraryList(emptyList(), emptyList(), rememberLazyListState(),
                        rememberLazyListState(), category.intValue, { category.intValue = it }, {}, {}, {},
                        showCloudEntry = false, offlineMode = true, onRecentClick = { clicks++ })
                }
            }
        }
        for (theme in listOf(UiStyle.Clean, UiStyle.Material)) {
            for (tab in listOf(NETEASE_CATEGORY_PLAYLIST, NETEASE_CATEGORY_ALBUM)) {
                compose.runOnIdle { style.value = theme; category.intValue = tab }
                compose.onNodeWithTag("netease-cloud-history-entry").assertIsDisplayed().performClick()
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("不匹配的歌单")
        compose.onNodeWithTag("netease-cloud-history-entry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(5, clicks) }
    }
}
