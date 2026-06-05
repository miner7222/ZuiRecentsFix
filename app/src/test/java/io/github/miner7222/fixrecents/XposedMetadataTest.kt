package io.github.miner7222.fixrecents

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedMetadataTest {

    @Test
    fun javaInitListsModernEntryPoint() {
        val entries = readLines(xposedMetadata("java_init.list"))

        assertEquals(listOf("io.github.miner7222.fixrecents.MainHook"), entries)
    }

    @Test
    fun modulePropTargetsApi101StaticScope() {
        val properties = Properties().apply {
            xposedMetadata("module.prop").inputStream().use(::load)
        }

        assertEquals("101", properties.getProperty("minApiVersion"))
        assertEquals("101", properties.getProperty("targetApiVersion"))
        assertEquals("true", properties.getProperty("staticScope"))
    }

    @Test
    fun scopeListContainsOnlyRequiredPackages() {
        val scopes = readLines(xposedMetadata("scope.list"))

        assertEquals(listOf("com.android.systemui", "com.zui.launcher"), scopes)
    }

    @Test
    fun legacyEntryFilesAreRemoved() {
        assertFalse(Path.of("src/main/assets/xposed_init").exists())
        assertFalse(Path.of("src/main/resources/META-INF/yukihookapi_init").exists())
    }

    private fun xposedMetadata(fileName: String): Path {
        val path = Path.of("src/main/resources/META-INF/xposed", fileName)
        assertTrue("$fileName should exist", path.exists())
        return path
    }

    private fun readLines(path: Path): List<String> {
        return Files.readAllLines(path)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
