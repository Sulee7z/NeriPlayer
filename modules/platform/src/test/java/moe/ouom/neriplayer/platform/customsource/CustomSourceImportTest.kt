package moe.ouom.neriplayer.platform.customsource

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CustomSourceImportTest {
    private val script = """
        /**
         * @name LX test
         * @description Compatible source
         * @version 2.0
         * @author Author
         * @homepage https://example.test
         */
        (function(){ throw new Error('must not run while importing'); })();
    """.trimIndent()

    @Test fun importAcceptsObfuscatedScriptsAndOnlyReadsLeadingMetadata() {
        val content = CustomSourceMetadataParser.normalizeForImport("\uFEFF$script\n// @name Wrong")
        val meta = CustomSourceMetadataParser.parse(content)
        assertEquals("LX test", meta.name)
        assertEquals("2.0", meta.version)
        assertEquals("Author", meta.author)
        assertEquals("https://example.test", meta.homepage)
        assertFalse(content.startsWith("\uFEFF"))
        assertFalse(CustomSourceMetadataParser.isImportable("<html>lx.on musicUrl request</html>"))
        assertFalse(CustomSourceMetadataParser.isImportable("// lx.on request\n"))
    }

    @Test fun metadataCannotGrowUnboundedOrBeInjectedFromTheScriptBody() {
        val content = "/**\n * @name ${"a".repeat(60)}\n */\nconst version = '@version 99';"
        val meta = CustomSourceMetadataParser.parse(content)
        assertEquals("a".repeat(24) + "...", meta.name)
        assertEquals("", meta.version)
        assertFalse(CustomSourceMetadataParser.isImportable("/** */" + "x".repeat(CustomSourceMetadataParser.MAX_SCRIPT_BYTES)))
    }

    @Test fun largeObfuscatedScriptsWithinLxMobileBudgetAreAccepted() {
        assertTrue(CustomSourceMetadataParser.isImportable(script + "\n/*" + "x".repeat(3_000_000) + "*/"))
    }

    @Test fun repeatedImportKeepsEnablementNameAndOrderAndSurvivesReload() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.filesDir.resolve("custom_sources").deleteRecursively()
        val repo = CustomSourceRepository(context)
        repo.load()
        val first = repo.importScript(script)
        assertFalse(first.enabled)
        assertEquals(script, repo.readScript(first))
        val second = repo.importScript(script.replace("LX test", "Second"))
        assertNotEquals(first.id, second.id)
        repo.rename(first.id, "My name")
        assertEquals("rename in memory", "My name", repo.sources.value.first().name)
        val disk = context.filesDir.resolve("custom_sources/index.json").readText()
        assertTrue("rename must be durable: $disk", disk.contains("My name"))
        repo.setEnabled(first.id, true)
        assertEquals("rename on disk", "My name", repo.sources.value.first().name)
        val repeated = repo.importScript("\uFEFF$script")
        assertEquals(first.id, repeated.id)
        assertEquals("My name", repeated.name)
        assertTrue(repeated.enabled)
        assertEquals(listOf(first.id, second.id), repo.sources.value.map { it.id })
        val reloaded = CustomSourceRepository(context)
        reloaded.load()
        assertEquals(repo.sources.value, reloaded.sources.value)
        assertEquals("https://example.test", reloaded.sources.value.first().homepage)
    }

    @Test fun malformedImportCannotReplaceAnInstalledSource() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.filesDir.resolve("custom_sources").deleteRecursively()
        val repo = CustomSourceRepository(context)
        val installed = repo.importScript(script)
        try { repo.importScript("<html>request</html>"); fail("Must reject HTML") }
        catch (_: IllegalArgumentException) { }
        assertEquals(listOf(installed), repo.sources.value)
        assertEquals(script, repo.readScript(installed))
    }

    @Test fun identicalImportRepairsAMissingFileWithoutResettingTheSource() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.filesDir.resolve("custom_sources").deleteRecursively()
        val repo = CustomSourceRepository(context)
        val source = repo.importScript(script)
        repo.rename(source.id, "Keep name")
        context.filesDir.resolve("custom_sources/${source.scriptFileName}").delete()
        val restored = repo.importScript(script)
        assertEquals("Keep name", restored.name)
        assertEquals(script, repo.readScript(restored))
        assertFalse(restored.enabled)
    }

    @Test fun legacyEnabledIndexWithoutHomepageKeepsItsAuthorizationAndPriority() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.filesDir.resolve("custom_sources").deleteRecursively()
        val repo = CustomSourceRepository(context)
        val source = repo.importScript(script)
        val legacy = source.copy(enabled = true, name = "Existing source").toJson().apply {
            remove("homepage")
        }
        context.filesDir.resolve("custom_sources/index.json")
            .writeText(org.json.JSONArray().put(legacy).toString())
        val reloaded = CustomSourceRepository(context)
        reloaded.load()
        val installed = reloaded.sources.value.single()
        assertTrue(installed.enabled)
        assertEquals("Existing source", installed.name)
        assertEquals("", installed.homepage)
        assertEquals(source.id, installed.id)
        assertEquals(installed, reloaded.importScript(script))
    }

    @Test fun legacyBomContentHashDoesNotDuplicateTheSameSourceOnReimport() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.filesDir.resolve("custom_sources").deleteRecursively()
        val repo = CustomSourceRepository(context)
        val source = repo.importScript(script)
        val id = "cs_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest("\uFEFF$script".toByteArray()).joinToString("") { "%02x".format(it) }
        val legacy = source.copy(id = id, scriptFileName = "$id.js", enabled = true)
        context.filesDir.resolve("custom_sources/${legacy.scriptFileName}").writeText("\uFEFF$script")
        context.filesDir.resolve("custom_sources/index.json")
            .writeText(org.json.JSONArray().put(legacy.toJson()).toString())
        val reloaded = CustomSourceRepository(context)
        reloaded.load()
        assertEquals(legacy, reloaded.importScript(script))
        assertEquals(1, reloaded.sources.value.size)
        assertTrue(reloaded.sources.value.single().enabled)
    }
}
