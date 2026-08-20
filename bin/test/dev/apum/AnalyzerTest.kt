package dev.apum

import dev.apum.analyze.PermissionAnalyzer
import dev.apum.cli.Cli
import dev.apum.cli.CliResult
import dev.apum.model.AnalysisReport
import dev.apum.model.PermissionStatus
import dev.apum.report.HtmlReporter
import dev.apum.report.JsonReporter
import dev.apum.source.SourceIndexer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalyzerTest {

    private val sampleProject = File("samples/demo-android-app")

    private val report: AnalysisReport by lazy { PermissionAnalyzer(sampleProject).analyze() }

    private fun permission(name: String) = report.permissions.firstOrNull { it.permission == name }

    @Test
    fun detectsDeclaredAndUsedPermission() {
        val camera = permission("android.permission.CAMERA")
        assertNotNull(camera)
        assertTrue(camera.declared)
        assertEquals(PermissionStatus.USED, camera.status)
        assertTrue(camera.runtimeRequests.isNotEmpty())
        assertTrue(camera.usages.any { it.file.endsWith("MainActivity.kt") })
    }

    @Test
    fun detectsUnusedPermission() {
        val contacts = permission("android.permission.READ_CONTACTS")
        assertNotNull(contacts)
        assertEquals(PermissionStatus.DECLARED_UNUSED, contacts.status)
        assertTrue(report.findings.any { it.rule == "UNUSED_PERMISSION" && it.permission == contacts.permission })
    }

    @Test
    fun detectsUndeclaredPermissionUsage() {
        val record = permission("android.permission.RECORD_AUDIO")
        assertNotNull(record)
        assertEquals(PermissionStatus.UNDECLARED_USAGE, record.status)
        assertTrue(report.findings.any { it.rule == "UNDECLARED_PERMISSION" && it.permission == record.permission })
    }

    @Test
    fun buildsCallPathsToEntryPoints() {
        val location = permission("android.permission.ACCESS_FINE_LOCATION")
        assertNotNull(location)
        assertTrue(location.callPaths.isNotEmpty())
        assertTrue(report.entryPoints.isNotEmpty())
    }

    @Test
    fun readsBuildConfiguration() {
        assertEquals("com.demo.app", report.project.applicationId)
        assertEquals(24, report.project.minSdk)
        assertEquals(34, report.project.targetSdk)
        assertTrue(report.project.dependencies.any { it.contains("okhttp") })
    }

    @Test
    fun parsesManifestComponents() {
        assertTrue(report.components.any { it.name == ".MainActivity" && it.exported == true })
        assertTrue(report.components.any { it.type == "receiver" })
        assertTrue(report.findings.any { it.rule == "CLEARTEXT_TRAFFIC" })
    }

    @Test
    fun flagsStorageAndPolicyRules() {
        assertTrue(report.findings.any { it.rule == "STORAGE_MAX_SDK" })
        assertTrue(report.findings.any { it.rule == "PLAY_POLICY_SENSITIVE" })
        assertTrue(report.findings.any { it.rule == "FINE_WITHOUT_COARSE" })
    }

    @Test
    fun producesSelfContainedHtmlAndJson() {
        val outputDir = File("build/test-output")
        val html = HtmlReporter.write(report, File(outputDir, "permission-map.html"))
        val json = JsonReporter.write(report, File(outputDir, "permission-map.json"))
        val htmlText = html.readText()
        assertTrue(htmlText.contains("apum-data"))
        assertTrue(htmlText.contains("permission-list"))
        assertTrue(!htmlText.contains("__APUM_DATA__"))
        assertTrue(json.readText().contains("android.permission.CAMERA"))
    }

    @Test
    fun stripsCommentsWithoutBreakingStrings() {
        val stripped = SourceIndexer.stripComments(
            listOf(
                "val url = \"https://example.com\" // trailing",
                "/* block */ val camera = Manifest.permission.CAMERA"
            )
        )
        assertTrue(stripped[0].contains("https://example.com"))
        assertTrue(!stripped[0].contains("trailing"))
        assertTrue(stripped[1].contains("Manifest.permission.CAMERA"))
    }

    @Test
    fun parsesCliArguments() {
        val parsed = Cli.parse(arrayOf(sampleProject.path, "--out", "build/tmp-report", "--fail-on", "high"))
        assertTrue(parsed is CliResult.Ok)
        val options = (parsed as CliResult.Ok).options
        assertEquals("HIGH", options.failOn)
        assertTrue(options.htmlEnabled)
    }

    @Test
    fun rejectsMissingProjectPath() {
        assertTrue(Cli.parse(arrayOf("--out", "x")) is CliResult.Error)
    }
}
