package dev.apum.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import dev.apum.analyze.AnalyzerOptions
import dev.apum.analyze.PermissionAnalyzer
import dev.apum.report.HtmlReporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.GraphicsEnvironment
import java.io.File
import java.net.BindException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.UIManager

class ApumServer(requestedPort: Int) {

    private val reports = ConcurrentHashMap<String, String>()
    private val reportOrder = ConcurrentLinkedDeque<String>()
    private val threadCounter = AtomicInteger(1)
    private val dialogOpen = AtomicBoolean(false)
    private val server: HttpServer = bind(requestedPort)

    val port: Int get() = server.address.port

    val url: String get() = "http://$HOST:$port"

    fun start() {
        server.executor = Executors.newFixedThreadPool(WORKER_COUNT) { runnable ->
            Thread(runnable, "apum-http-" + threadCounter.getAndIncrement())
        }
        server.createContext("/", LauncherHandler())
        server.createContext("/api/health", HealthHandler())
        server.createContext("/api/pick-folder", PickFolderHandler())
        server.createContext("/api/analyze", AnalyzeHandler())
        server.createContext("/report/", ReportHandler())
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    private fun bind(requestedPort: Int): HttpServer =
        try {
            HttpServer.create(InetSocketAddress(HOST, requestedPort), BACKLOG)
        } catch (error: BindException) {
            HttpServer.create(InetSocketAddress(HOST, 0), BACKLOG)
        }

    private inner class LauncherHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) = exchange.guard {
            val path = exchange.requestURI.path
            if (path != "/" && path != "/index.html") {
                exchange.respond(404, TYPE_JSON, errorJson("Not found: " + path))
                return@guard
            }
            exchange.respond(200, TYPE_HTML, launcherPage())
        }
    }

    private inner class HealthHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) = exchange.guard {
            val body = buildJsonObject {
                put("status", "ok")
                put("port", port)
                put("reports", reports.size)
            }
            exchange.respond(200, TYPE_JSON, body.toString())
        }
    }

    private inner class PickFolderHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) = exchange.guard {
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                exchange.respond(405, TYPE_JSON, errorJson("Use POST for /api/pick-folder"))
                return@guard
            }
            if (!dialogOpen.compareAndSet(false, true)) {
                exchange.respond(409, TYPE_JSON, errorJson("A folder dialog is already open"))
                return@guard
            }
            try {
                val picked = runCatching { chooseFolder() }.getOrElse { failure ->
                    val reason = failure.message ?: failure::class.java.simpleName
                    exchange.respond(500, TYPE_JSON, errorJson("Folder dialog is not available: " + reason))
                    return@guard
                }
                val payload = buildJsonObject {
                    if (picked == null) put("path", JsonNull) else put("path", picked)
                }
                exchange.respond(200, TYPE_JSON, payload.toString())
            } finally {
                dialogOpen.set(false)
            }
        }
    }

    private inner class AnalyzeHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) = exchange.guard {
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                exchange.respond(405, TYPE_JSON, errorJson("Use POST for /api/analyze"))
                return@guard
            }
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val request = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (request == null) {
                exchange.respond(400, TYPE_JSON, errorJson("Request body must be a JSON object"))
                return@guard
            }
            val rawPath = runCatching { request["path"]?.jsonPrimitive?.content }.getOrNull().orEmpty().trim()
            if (rawPath.isEmpty()) {
                exchange.respond(400, TYPE_JSON, errorJson("No project path was given"))
                return@guard
            }
            val includeTests = runCatching { request["includeTests"]?.jsonPrimitive?.boolean }.getOrNull() ?: false
            val projectRoot = runCatching { File(rawPath).canonicalFile }.getOrNull()
            if (projectRoot == null || !projectRoot.isDirectory) {
                exchange.respond(400, TYPE_JSON, errorJson("Project path not found: " + rawPath))
                return@guard
            }

            val analysis = runCatching {
                PermissionAnalyzer(projectRoot, AnalyzerOptions(includeTests = includeTests)).analyze()
            }
            val report = analysis.getOrElse { failure ->
                val reason = failure.message ?: failure::class.java.simpleName
                exchange.respond(400, TYPE_JSON, errorJson("Analysis failed: " + reason))
                return@guard
            }

            val id = UUID.randomUUID().toString().replace("-", "").take(12)
            store(id, HtmlReporter.render(report))

            val summary = report.summary
            val payload = buildJsonObject {
                put("id", id)
                put("projectName", report.project.name)
                put("projectPath", report.project.path)
                put("applicationId", report.project.applicationId ?: "")
                put("riskGrade", summary.riskGrade)
                put("riskScore", summary.riskScore)
                put("declaredPermissionCount", summary.declaredPermissionCount)
                put("usedPermissionCount", summary.usedPermissionCount)
                put("unusedPermissionCount", summary.unusedPermissionCount)
                put("undeclaredPermissionCount", summary.undeclaredPermissionCount)
                put("dangerousPermissionCount", summary.dangerousPermissionCount)
                put("findingsCount", report.findings.size)
                put("analysisDurationMs", summary.analysisDurationMs)
                put("reportUrl", "/report/" + id)
            }
            exchange.respond(200, TYPE_JSON, payload.toString())
        }
    }

    private inner class ReportHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) = exchange.guard {
            val id = exchange.requestURI.path.removePrefix("/report/").substringBefore('/')
            val stored = reports[id]
            if (stored == null) {
                exchange.respond(404, TYPE_HTML, missingReportPage())
                return@guard
            }
            exchange.respond(200, TYPE_HTML, stored)
        }
    }

    private fun store(id: String, html: String) {
        reports[id] = html
        reportOrder.addLast(id)
        while (reportOrder.size > MAX_REPORTS) {
            val oldest = reportOrder.pollFirst() ?: break
            reports.remove(oldest)
        }
    }

    private fun chooseFolder(): String? {
        if (GraphicsEnvironment.isHeadless()) {
            throw IllegalStateException("this machine has no desktop session")
        }
        val selection = AtomicReference<String?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val task = Runnable {
            var anchor: JFrame? = null
            try {
                runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
                anchor = JFrame().apply {
                    isUndecorated = true
                    isAlwaysOnTop = true
                    setSize(1, 1)
                    setLocationRelativeTo(null)
                    isVisible = true
                    toFront()
                    requestFocus()
                }
                val chooser = JFileChooser().apply {
                    dialogTitle = DIALOG_TITLE
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isMultiSelectionEnabled = false
                    isAcceptAllFileFilterUsed = false
                }
                if (chooser.showOpenDialog(anchor) == JFileChooser.APPROVE_OPTION) {
                    selection.set(chooser.selectedFile?.absoluteFile?.path)
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                anchor?.dispose()
            }
        }
        if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
        failure.get()?.let { throw it }
        return selection.get()
    }

    private fun launcherPage(): String = resource("/web/launcher.html")
        .replace("/*__APUM_LAUNCHER_STYLES__*/", resource("/web/launcher.css"))
        .replace("/*__APUM_LAUNCHER_SCRIPT__*/", resource("/web/launcher.js"))

    private fun missingReportPage(): String = resource("/web/report-missing.html")

    private fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()

    private fun HttpExchange.guard(block: () -> Unit) {
        try {
            use { block() }
        } catch (error: Exception) {
            runCatching { respond(500, TYPE_JSON, errorJson(error.message ?: "Unexpected server error")) }
        }
    }

    private fun HttpExchange.respond(status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", contentType)
        responseHeaders.set("Cache-Control", "no-store")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun resource(path: String): String =
        ApumServer::class.java.getResourceAsStream(path)?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
            ?: error("Missing bundled resource: " + path)

    private companion object {
        const val HOST = "127.0.0.1"
        const val BACKLOG = 0
        const val WORKER_COUNT = 4
        const val MAX_REPORTS = 20
        const val TYPE_JSON = "application/json; charset=utf-8"
        const val TYPE_HTML = "text/html; charset=utf-8"
        const val DIALOG_TITLE = "Select project folder"

        val json = Json { ignoreUnknownKeys = true }
    }
}
