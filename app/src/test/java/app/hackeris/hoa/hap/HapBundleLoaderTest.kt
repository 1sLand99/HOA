package app.hackeris.hoa.hap

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class HapBundleLoaderTest {

    private lateinit var testHap: File

    @Before
    fun setUp() {
        // test_app.hap is built from the module.json + .abc in assets
        val resourcePath = javaClass.classLoader
            ?.getResource("test_app.hap")?.path
            ?: throw IllegalStateException("test_app.hap not found in test resources")
        testHap = File(resourcePath)
    }

    @Test
    fun parse_moduleConfig() {
        val bundle = HapBundleLoader().parse(testHap.absolutePath)

        val config = bundle.moduleConfig
        assertEquals("com.example.enjoyarkuix", config.bundleName)
        assertEquals("dynamicHap", config.name)
        assertEquals("feature", config.type)
        assertEquals("DynamicHapAbility", config.mainElement)
        assertEquals("ark12.0.6.0", config.virtualMachine)
    }

    @Test
    fun parse_abilities() {
        val bundle = HapBundleLoader().parse(testHap.absolutePath)

        val abilities = bundle.moduleConfig.abilities
        assertTrue("Should have at least one ability", abilities.isNotEmpty())

        val mainAbility = abilities.first()
        assertEquals("DynamicHapAbility", mainAbility.name)
        assertEquals("page", mainAbility.type)
        assertTrue(mainAbility.exported)
    }

    @Test
    fun parse_pages() {
        val bundle = HapBundleLoader().parse(testHap.absolutePath)

        val pages = bundle.moduleConfig.pages
        assertTrue("Should have at least one page", pages.isNotEmpty())
        // The test HAP has $profile:main_pages.json which contains pages/Index
        assertTrue(pages.any { it.contains("Index") })
    }

    @Test
    fun extract_bytecode() {
        val bundle = HapBundleLoader().parse(testHap.absolutePath)

        assertTrue("Should contain modules.abc", bundle.bytecodeEntries.containsKey("ets/modules.abc"))
        val abc = bundle.bytecodeEntries["ets/modules.abc"]!!
        assertTrue("modules.abc should not be empty", abc.isNotEmpty())

        // Verify .abc magic header: PANDA\0\0\0
        assertEquals('P'.code.toByte(), abc[0])
        assertEquals('A'.code.toByte(), abc[1])
        assertEquals('N'.code.toByte(), abc[2])
        assertEquals('D'.code.toByte(), abc[3])
        assertEquals('A'.code.toByte(), abc[4])
    }

    @Test
    fun extract_resourceIndex() {
        val bundle = HapBundleLoader().parse(testHap.absolutePath)
        assertNotNull("resources.index should be present", bundle.resourceIndex)
        assertTrue("resources.index should not be empty", bundle.resourceIndex!!.isNotEmpty())
    }

    @Test
    fun extract_resources() {
        val bundle = HapBundleLoader().parse(testHap.absolutePath)

        // Should have files under resources/
        assertTrue("Should have resource files", bundle.rawResources.isNotEmpty())
        assertTrue(
            "Should have main_pages.json",
            bundle.rawResources.keys.any { it.contains("main_pages") }
        )
    }

    @Test(expected = HapParseException::class)
    fun parse_invalidHap_throwsException() {
        // Create a temp file that's not a valid HAP
        val tmpFile = File.createTempFile("invalid", ".hap")
        tmpFile.deleteOnExit()
        tmpFile.writeText("not a zip file")

        HapBundleLoader().parse(tmpFile.absolutePath)
    }

    @Test(expected = HapParseException::class)
    fun parse_hapWithoutModuleJson_throwsException() {
        // Create a ZIP without module.json
        val tmpFile = File.createTempFile("no_module", ".hap")
        tmpFile.deleteOnExit()
        java.util.zip.ZipOutputStream(tmpFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("dummy.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }

        HapBundleLoader().parse(tmpFile.absolutePath)
    }

    @Test
    fun parse_abcBytecodeVersion() {
        val bundle = HapBundleLoader().parse(testHap.absolutePath)
        val abc = bundle.bytecodeEntries["ets/modules.abc"]
        assertNotNull("No modules.abc", abc)
        abc!!

        // .abc version is at offset 0x0C (4 bytes): [major, minor, feature, build]
        val major = abc[0x0C].toInt() and 0xFF
        val minor = abc[0x0D].toInt() and 0xFF
        val feature = abc[0x0E].toInt() and 0xFF
        val build = abc[0x0F].toInt() and 0xFF

        // This HAP was compiled with ark12.0.6.0
        assertEquals(12, major)
        assertEquals(0, minor)
        assertEquals(6, feature)
    }
}
