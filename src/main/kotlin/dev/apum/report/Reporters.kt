package dev.apum.report

import dev.apum.model.AnalysisReport
import dev.apum.model.PermissionStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object JsonReporter {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val compact = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun toPrettyJson(report: AnalysisReport): String = json.encodeToString(report)

    fun toCompactJson(report: AnalysisReport): String = compact.encodeToString(report)

    fun write(report: AnalysisReport, target: File): File {
        target.parentFile?.mkdirs()
        target.writeText(toPrettyJson(report), Charsets.UTF_8)
        return target
    }
}

object HtmlReporter {

    fun render(report: AnalysisReport): String {
        val template = resource("/web/index.html")
        val styles = resource("/web/styles.css")
        val script = resource("/web/app.js")
        val data = JsonReporter.toCompactJson(report).replace("</", "<\\/")

        return template
            .replace("/*__APUM_STYLES__*/", styles)
            .replace("/*__APUM_SCRIPT__*/", script)
            .replace("__APUM_DATA__", data)
            .replace("__APUM_PROJECT_NAME__", report.project.name)
    }

    fun write(report: AnalysisReport, target: File): File {
        target.parentFile?.mkdirs()
        target.writeText(render(report), Charsets.UTF_8)
        return target
    }

    private fun resource(path: String): String =
        HtmlReporter::class.java.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Missing bundled resource: $path")
}

object ConsoleReporter {

    fun render(report: AnalysisReport): String {
        val builder = StringBuilder()
        val summary = report.summary

        builder.appendLine("Android Permission Usage Mapper")
        builder.appendLine("Project      : ${report.project.name}")
        builder.appendLine("Path         : ${report.project.path}")
        builder.appendLine("Application  : ${report.project.applicationId ?: "-"}")
        builder.appendLine("SDK          : min ${report.project.minSdk ?: "-"} / target ${report.project.targetSdk ?: "-"}")
        builder.appendLine("Sources      : ${report.project.kotlinFileCount} Kotlin, ${report.project.javaFileCount} Java, ${report.project.dartFileCount} Dart")
        builder.appendLine("Duration     : ${summary.analysisDurationMs} ms")
        builder.appendLine()
        builder.appendLine("Risk grade   : ${summary.riskGrade} (score ${summary.riskScore}/100)")
        builder.appendLine("Permissions  : ${summary.declaredPermissionCount} declared, ${summary.usedPermissionCount} declared and used, ${summary.unusedPermissionCount} declared but never used, ${summary.undeclaredPermissionCount} missing from manifest")
        builder.appendLine("               Every permission falls into exactly one state, the categories never overlap.")
        builder.appendLine()

        builder.appendLine("PERMISSION MAP")
        report.permissions.forEach { permission ->
            val marker = when (permission.status) {
                PermissionStatus.USED -> "[DECLARED+USED]"
                PermissionStatus.DECLARED_UNUSED -> "[DECLARED+NEVER-USED]"
                PermissionStatus.UNDECLARED_USAGE -> "[MISSING-FROM-MANIFEST]"
                PermissionStatus.REQUESTED_ONLY -> "[REQUEST-ONLY]"
                PermissionStatus.WEAK_SIGNAL -> "[WEAK]"
                PermissionStatus.ALTERNATIVE_COVERED -> "[ALT-COVERED]"
                else -> "[?]"
            }
            builder.appendLine("  $marker ${permission.shortName} (${permission.protection}) risk=${permission.riskLevel} usages=${permission.usages.size} paths=${permission.callPaths.size}")
            permission.usages.take(3).forEach { usage ->
                builder.appendLine("      ${usage.file}:${usage.line}  ${usage.evidence}")
            }
        }
        builder.appendLine()

        builder.appendLine("FINDINGS (${report.findings.size})")
        report.findings.forEach { finding ->
            builder.appendLine("  ${finding.severity.padEnd(8)} ${finding.id} ${finding.title}")
        }
        if (report.findings.isEmpty()) builder.appendLine("  No issues found.")

        return builder.toString()
    }
}
