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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import android.graphics.Canvas
import org.robolectric.shadows.ShadowDialog
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.graphics.Bitmap
import java.io.File
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
import moe.ouom.neriplayer.platform.customsource.CustomAudioSource
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
            capture("source-${theme.name.lowercase()}")
            compose.runOnIdle { assertEquals(SettingsPage.CustomSource, page.value) }
        }
    }

    // Robolectric #8460: Dialog + TextField loops with width qualifiers above its 320dp window.
    // Keep the end-to-end input/cancel assertions; test wider page layouts independently above.
    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h1000dp-mdpi")
    fun scriptDialogAcceptsHeaderWithoutApiKeywordsAndCanBeCancelledInBothThemes() {
        val style = mutableStateOf(UiStyle.Clean)
        val vm = mock(CustomSourceViewModel::class.java)
        `when`(vm.uiState).thenReturn(MutableStateFlow(CustomSourceUiState()))
        compose.setContent {
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "885522", uiStyle = style.value, animateColors = false) {
                Surface { CustomSourceScreen(onBack = {}, sourceViewModel = vm) }
            }
        }
        for (theme in listOf(UiStyle.Clean, UiStyle.Material)) {
            compose.runOnIdle { style.value = theme }
            compose.onNodeWithText("粘贴脚本").performScrollTo().performClick()
            compose.mainClock.advanceTimeBy(500L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            compose.onNodeWithText("// LX Music source script...").performTextInput(
                "/**\n * @name Obfuscated source\n */\n(function(){ return 1; })();")
            compose.onNodeWithText("导入", useUnmergedTree = true).assertIsEnabled()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("// LX Music source script...").assertDoesNotExist()
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h800dp-mdpi")
    fun importButtonsWrapAtNarrowWidths() {
        val vm = mock(CustomSourceViewModel::class.java)
        `when`(vm.uiState).thenReturn(MutableStateFlow(CustomSourceUiState()))
        compose.setContent {
            NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "885522", uiStyle = UiStyle.Clean, animateColors = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Surface { CustomSourceScreen(onBack = {}, sourceViewModel = vm) }
                }
            }
        }
        for (label in listOf("从文件导入", "粘贴脚本", "从 URL 导入")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        capture("source-narrow")
    }

    private fun capture(name: String) {
        val directory = System.getenv("NERIPLAYER_UI_SCREENSHOT_DIR") ?: return
        val image = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val file = File(directory, "$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun installedSourceRetainsControlsInBothDarkThemes() {
        val style = mutableStateOf(UiStyle.Clean)
        val vm = mock(CustomSourceViewModel::class.java)
        val source = CustomAudioSource(id = "cs_test", name = "已导入的 LX 音源",
            version = "2.0", author = "Source author", description = "Test source", scriptFileName = "cs_test.js",
            enabled = true, supportedSources = mapOf("wy" to listOf("128k", "320k", "flac")))
        `when`(vm.uiState).thenReturn(MutableStateFlow(CustomSourceUiState(sources = listOf(source))))
        compose.setContent {
            NeriTheme(followSystemDark = false, forceDark = true, dynamicColor = false,
                seedColorHex = "885522", uiStyle = style.value, animateColors = false) {
                Surface { CustomSourceScreen(onBack = {}, sourceViewModel = vm) }
            }
        }
        for (theme in listOf(UiStyle.Clean, UiStyle.Material)) {
            compose.runOnIdle { style.value = theme }
            compose.onNodeWithText("已导入的 LX 音源").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("测试此音源").performScrollTo().assertIsDisplayed()
            capture("source-${theme.name.lowercase()}-dark-installed")
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
