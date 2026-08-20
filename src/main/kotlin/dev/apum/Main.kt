package dev.apum

import dev.apum.analyze.AnalyzerOptions
import dev.apum.analyze.PermissionAnalyzer
import dev.apum.cli.Cli
import dev.apum.cli.CliResult
import dev.apum.model.Severity
import dev.apum.report.ConsoleReporter
import dev.apum.report.HtmlReporter
import dev.apum.report.JsonReporter
import dev.apum.server.ApumServer
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (val parsed = Cli.parse(args)) {
        is CliResult.Help -> {
            println(parsed.message.trim())
            exitProcess(0)
        }
        is CliResult.Error -> {
            System.err.println("Error: ${parsed.message}")
            System.err.println(Cli.USAGE.trim())
            exitProcess(1)
        }
        is CliResult.Serve -> serve(parsed.port)
        is CliResult.Ok -> {
            val options = parsed.options
            val report = PermissionAnalyzer(
                projectRoot = options.projectPath,
                options = AnalyzerOptions(includeTests = options.includeTests)
            ).analyze()

            options.outputDir.mkdirs()
            val generated = mutableListOf<File>()
            if (options.jsonEnabled) generated += JsonReporter.write(report, File(options.outputDir, "permission-map.json"))
            val htmlFile = if (options.htmlEnabled) {
                HtmlReporter.write(report, File(options.outputDir, "permission-map.html")).also { generated += it }
            } else {
                null
            }

            if (!options.quiet) {
                println(ConsoleReporter.render(report))
                generated.forEach { println("Report: ${it.absolutePath}") }
            }

            if (options.openReport && htmlFile != null) openInBrowser(htmlFile)

            val threshold = options.failOn
            if (threshold != null) {
                val order = listOf(
                    Severity.CRITICAL.label,
                    Severity.HIGH.label,
                    Severity.MEDIUM.label,
                    Severity.LOW.label
                )
                val limit = order.indexOf(threshold)
                val breaching = report.findings.count { order.indexOf(it.severity).let { it in 0..limit } }
                if (breaching > 0) {
                    System.err.println("$breaching finding(s) at $threshold severity or above.")
                    exitProcess(2)
                }
            }
            exitProcess(0)
        }
    }
}

private fun serve(port: Int) {
    val server = try {
        ApumServer(port).also { it.start() }
    } catch (error: IOException) {
        System.err.println("Could not start the server: ${error.message}")
        exitProcess(1)
    }

    val stopSignal = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop()
            stopSignal.countDown()
        }
    )

    println("Android Permission Usage Mapper")
    println("Launcher : ${server.url}")
    println("Health   : ${server.url}/api/health")
    println("Press Ctrl+C to stop the server.")
    openInBrowser(URI(server.url))

    stopSignal.await()
}

private fun openInBrowser(file: File) = openInBrowser(file.toURI())

private fun openInBrowser(target: URI) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(target)
        }
    }
}
