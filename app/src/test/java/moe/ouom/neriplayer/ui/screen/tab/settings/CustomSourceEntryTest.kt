package moe.ouom.neriplayer.ui.screen.tab.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.SettingsPageScaffold
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsPageGroupCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsResponsiveDetailScaffold
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsHomePageGroups
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.buildSettingsSearchEntries
import moe.ouom.neriplayer.ui.screen.tab.settings.page.searchSettingsEntries
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.screen.customsource.CustomSourceScreen
import moe.ouom.neriplayer.ui.viewmodel.customsource.CustomSourceViewModel
import moe.ouom.neriplayer.ui.viewmodel.customsource.CustomSourceUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w400dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomSourceEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun customSourceIsInPlaybackSettingsAndOpensItsOwnRouteInBothThemes() {
        val page = mutableStateOf<SettingsPage?>(null)
        val style = mutableStateOf(UiStyle.Clean)
        val group = SettingsHomePageGroups.first { SettingsPage.Playback in it }
        val vm = mock(CustomSourceViewModel::class.java)
        `when`(vm.uiState).thenReturn(MutableStateFlow(CustomSourceUiState()))
        assertTrue(SettingsPage.CustomSource in group)
        compose.setContent {
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "885522", uiStyle = style.value, animateColors = false) {
                Surface {
                    SettingsPageScaffold(page.value,
                        home = { MiuixSettingsPageGroupCard(group, { page.value = it }) },
                        detail = { selected ->
                            if (selected == SettingsPage.CustomSource) {
                                CustomSourceScreen(onBack = { page.value = null }, sourceViewModel = vm)
                            }
                        })
                }
            }
        }
        for (theme in listOf(UiStyle.Clean, UiStyle.Material)) {
            compose.runOnIdle { style.value = theme; page.value = null }
            compose.onNodeWithText("自定义音源").performClick()
            compose.onNodeWithTag("custom-source-manager").assertIsDisplayed()
            compose.onNodeWithText("粘贴脚本").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals(SettingsPage.CustomSource, page.value) }
        }
    }

    @Test fun playbackSourcePageLinkInvokesNavigation() {
        var clicks = 0
        compose.setContent { Surface { Column { CustomSourceSettingsLink { clicks++ } } } }
        compose.onNodeWithTag("settings-custom-source-entry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun settingsSearchFindsTheActualSourceRouteByNameOrLx() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        val entries = buildSettingsSearchEntries(resources)
        for (query in listOf("自定义音源", "洛雪", "LX")) {
            assertTrue("Missing source route for $query", searchSettingsEntries(entries, query)
                .any { it.page == SettingsPage.CustomSource && it.id == "page:CustomSource" })
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    @Config(qualifiers = "zh-rCN-sw840dp-w1000dp-h900dp-mdpi")
    fun tabletRetainsNavigationAlongsideTheActualCustomSourceManager() {
        val vm = mock(CustomSourceViewModel::class.java)
        `when`(vm.uiState).thenReturn(MutableStateFlow(CustomSourceUiState()))
        compose.setContent {
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "885522", uiStyle = UiStyle.Clean, animateColors = false) {
                Surface {
                    val list = rememberLazyListState()
                    val bar = rememberTopAppBarState()
                    MiuixSettingsResponsiveDetailScaffold(
                        title = "自定义音源", onBack = {}, listState = list, topAppBarState = bar,
                        splitLayout = true, selectedPage = SettingsPage.CustomSource,
                        homeListState = rememberLazyListState(), homeTopAppBarState = rememberTopAppBarState(),
                        homeTitle = { Text("设置导航") }, homeContent = { item { Text("settings-navigation") } },
                        customPageContent = mapOf(SettingsPage.CustomSource to {
                            CustomSourceScreen(onBack = {}, sourceViewModel = vm)
                        })
                    ) { item { Text("unexpected-empty-detail") } }
                }
            }
        }
        compose.onNodeWithText("settings-navigation").assertIsDisplayed()
        compose.onNodeWithTag("custom-source-manager").assertIsDisplayed()
        compose.onNodeWithText("从文件导入").assertIsDisplayed()
    }
}
