package dev.apum.cli

import java.io.File

data class CliOptions(
    val projectPath: File,
    val outputDir: File,
    val includeTests: Boolean,
    val htmlEnabled: Boolean,
    val jsonEnabled: Boolean,
    val openReport: Boolean,
    val failOn: String?,
    val quiet: Boolean
)

sealed class CliResult {
    data class Ok(val options: CliOptions) : CliResult()
    data class Serve(val port: Int) : CliResult()
    data class Help(val message: String) : CliResult()
    data class Error(val message: String) : CliResult()
}

object Cli {

    const val DEFAULT_PORT = 7473

    const val USAGE = """
Android Permission Usage Mapper

Usage:
  apum <android-project-path> [options]
  apum --serve [--port <n>]

Options:
  --serve              Start the local web launcher, pick a folder in the browser
  --port <n>           Port for --serve (default: 7473, a free port is used when busy)
  --out <dir>          Directory the reports are written to (default: <project>/apum-report)
  --include-tests      Include test sources in the analysis
  --no-html            Skip the HTML report
  --no-json            Skip the JSON report
  --open               Open the HTML report in the default browser when done
  --fail-on <level>    Exit with code 2 when a CRITICAL|HIGH|MEDIUM|LOW finding exists
  --quiet              Do not print the console summary
  -h, --help           Show this help text

Examples:
  apum C:/projects/my-app --out C:/reports --fail-on HIGH --open
  apum --serve --port 7473
"""

    fun parse(args: Array<String>): CliResult {
        if (args.isEmpty() || args.any { it == "-h" || it == "--help" }) return CliResult.Help(USAGE)

        var projectPath: File? = null
        var outputDir: File? = null
        var includeTests = false
        var html = true
        var json = true
        var open = false
        var failOn: String? = null
        var quiet = false
        var serve = false
        var port = DEFAULT_PORT

        var index = 0
        while (index < args.size) {
            when (val argument = args[index]) {
                "--out" -> {
                    val value = args.getOrNull(++index) ?: return CliResult.Error("--out requires a value")
                    outputDir = File(value)
                }
                "--fail-on" -> {
                    val value = args.getOrNull(++index) ?: return CliResult.Error("--fail-on requires a value")
                    val normalized = value.uppercase()
                    if (normalized !in setOf("CRITICAL", "HIGH", "MEDIUM", "LOW")) {
                        return CliResult.Error("Invalid --fail-on value: $value")
                    }
                    failOn = normalized
                }
                "--port" -> {
                    val value = args.getOrNull(++index) ?: return CliResult.Error("--port requires a value")
                    val parsedPort = value.toIntOrNull()
                        ?: return CliResult.Error("Invalid --port value: $value")
                    if (parsedPort !in 1..65535) return CliResult.Error("Port must be between 1 and 65535")
                    port = parsedPort
                }
                "--serve" -> serve = true
                "--include-tests" -> includeTests = true
                "--no-html" -> html = false
                "--no-json" -> json = false
                "--open" -> open = true
                "--quiet" -> quiet = true
                else -> {
                    if (argument.startsWith("--")) return CliResult.Error("Unknown option: $argument")
                    if (projectPath != null) return CliResult.Error("More than one project path was given")
                    projectPath = File(argument)
                }
            }
            index++
        }

        if (serve) return CliResult.Serve(port)

        val resolvedProject = projectPath ?: return CliResult.Error("No project path was given")
        if (!resolvedProject.exists() || !resolvedProject.isDirectory) {
            return CliResult.Error("Project path not found: ${resolvedProject.absolutePath}")
        }
        if (!html && !json) return CliResult.Error("--no-html and --no-json cannot be used together")

        return CliResult.Ok(
            CliOptions(
                projectPath = resolvedProject.absoluteFile,
                outputDir = (outputDir ?: File(resolvedProject, "apum-report")).absoluteFile,
                includeTests = includeTests,
                htmlEnabled = html,
                jsonEnabled = json,
                openReport = open,
                failOn = failOn,
                quiet = quiet
            )
        )
    }
}
