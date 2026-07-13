package xx.biketracker

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards the privacy promise from README.md: the location database must never reach
 * Android cloud backup or device-to-device transfer. Unit tests run with the module
 * directory as the working directory, so the manifest and resources are read directly.
 */
class BackupPolicyTest {

    private val manifest = File("src/main/AndroidManifest.xml")
    private val extractionRules = File("src/main/res/xml/data_extraction_rules.xml")

    private fun parse(file: File): Element {
        assertTrue("${file.path} must exist", file.isFile)
        return DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    @Test
    fun platformBackupIsDisabled() {
        val application = parse(manifest).getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttribute("android:allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
    }

    @Test
    fun extractionRulesExcludeEveryDomainAndIncludeNothing() {
        val root = parse(extractionRules)
        assertEquals(0, root.getElementsByTagName("include").length)

        val expectedDomains = setOf("root", "file", "database", "sharedpref", "external")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = root.getElementsByTagName(section)
            assertEquals("one <$section> section", 1, nodes.length)
            val excludes = (nodes.item(0) as Element).getElementsByTagName("exclude")
            val domains = (0 until excludes.length)
                .map { (excludes.item(it) as Element).getAttribute("domain") }
                .toSet()
            assertEquals("<$section> must exclude every domain", expectedDomains, domains)
        }
    }
}
