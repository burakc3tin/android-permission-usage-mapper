package dev.apum.analyze

import dev.apum.build.BuildScanner
import dev.apum.catalog.PermissionCatalog
import dev.apum.catalog.Protection
import dev.apum.graph.CallGraph
import dev.apum.manifest.ManifestParser
import dev.apum.manifest.ParsedManifest
import dev.apum.model.AnalysisReport
import dev.apum.model.CallPath
import dev.apum.model.CallPathNode
import dev.apum.model.ComponentInfo
import dev.apum.model.Confidence
import dev.apum.model.EntryPointInfo
import dev.apum.model.Finding
import dev.apum.model.ManifestDeclaration
import dev.apum.model.ModuleInfo
import dev.apum.model.PermissionReport
import dev.apum.model.PermissionStatus
import dev.apum.model.ProjectInfo
import dev.apum.model.Severity
import dev.apum.model.Summary
import dev.apum.model.Usage
import dev.apum.source.SourceFile
import dev.apum.source.SourceIndexer
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

data class AnalyzerOptions(
    val includeTests: Boolean = false,
    val minConfidence: Confidence = Confidence.LOW
)

object UsageKind {
    const val API_CALL = "API_CALL"
    const val REQUEST = "REQUEST"
    const val CHECK = "CHECK"
    const val PERMISSION_STRING = "PERMISSION_STRING"
    const val MANIFEST = "MANIFEST"
}

private val MANIFEST_CONSTANT = Regex("""Manifest\.permission\.([A-Z0-9_]+)""")
private val PERMISSION_LITERAL = Regex(""""((?:android|com\.android\.vending|com\.google\.android\.gms)[\w.]*\.(?:permission\.)?[A-Z0-9_]+)"""")
private val REQUEST_HINTS = listOf(
    "requestPermissions", "RequestPermission", "RequestMultiplePermissions",
    "PermissionLauncher", "launchPermissionRequest", "shouldShowRequestPermissionRationale",
    "PermissionState", "rememberPermissionState", "launcher.launch"
)
private val DART_PERMISSION_REGEX = Regex("""\bPermission\.([a-zA-Z]\w*)""")
private val DART_PERMISSION_MAP: Map<String, List<String>> = mapOf(
    "camera" to listOf("android.permission.CAMERA"),
    "microphone" to listOf("android.permission.RECORD_AUDIO"),
    "speech" to listOf("android.permission.RECORD_AUDIO"),
    "location" to listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
    "locationWhenInUse" to listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
    "locationAlways" to listOf("android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.ACCESS_FINE_LOCATION"),
    "photos" to listOf("android.permission.READ_MEDIA_IMAGES"),
    "photosAddOnly" to listOf("android.permission.READ_MEDIA_IMAGES"),
    "videos" to listOf("android.permission.READ_MEDIA_VIDEO"),
    "audio" to listOf("android.permission.READ_MEDIA_AUDIO"),
    "storage" to listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"),
    "manageExternalStorage" to listOf("android.permission.MANAGE_EXTERNAL_STORAGE"),
    "notification" to listOf("android.permission.POST_NOTIFICATIONS"),
    "contacts" to listOf("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"),
    "calendar" to listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
    "calendarFullAccess" to listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR"),
    "calendarWriteOnly" to listOf("android.permission.WRITE_CALENDAR"),
    "phone" to listOf("android.permission.CALL_PHONE", "android.permission.READ_PHONE_STATE"),
    "sms" to listOf("android.permission.SEND_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_SMS"),
    "bluetoothScan" to listOf("android.permission.BLUETOOTH_SCAN"),
    "bluetoothConnect" to listOf("android.permission.BLUETOOTH_CONNECT"),
    "bluetoothAdvertise" to listOf("android.permission.BLUETOOTH_ADVERTISE"),
    "nearbyWifiDevices" to listOf("android.permission.NEARBY_WIFI_DEVICES"),
    "sensors" to listOf("android.permission.BODY_SENSORS"),
    "activityRecognition" to listOf("android.permission.ACTIVITY_RECOGNITION"),
    "scheduleExactAlarm" to listOf("android.permission.SCHEDULE_EXACT_ALARM"),
    "systemAlertWindow" to listOf("android.permission.SYSTEM_ALERT_WINDOW"),
    "requestInstallPackages" to listOf("android.permission.REQUEST_INSTALL_PACKAGES")
)
private val ALTERNATIVE_GROUPS = listOf(
    setOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
    setOf(
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO"
    ),
    setOf("android.permission.SCHEDULE_EXACT_ALARM", "android.permission.USE_EXACT_ALARM")
)
private val CHECK_HINTS = listOf(
    "checkSelfPermission", "PERMISSION_GRANTED", "checkPermission", "isGranted", "hasPermission"
)

class PermissionAnalyzer(
    private val projectRoot: File,
    private val options: AnalyzerOptions = AnalyzerOptions()
) {

    fun analyze(): AnalysisReport {
        val startedAt = System.currentTimeMillis()
        val buildInfo = BuildScanner(projectRoot).scan()
        val manifests = ManifestParser(projectRoot).parseAll()
        val sourceFiles = SourceIndexer(projectRoot, options.includeTests).index()
        val graph = CallGraph(sourceFiles)

        val usagesByPermission = detectUsages(sourceFiles, graph)
        val declarationsByPermission = declarations(manifests)

        val allPermissions = (usagesByPermission.keys + declarationsByPermission.keys).sorted()

        val permissionReports = allPermissions.map { permission ->
            buildPermissionReport(
                permission = permission,
                declarations = declarationsByPermission[permission].orEmpty(),
                usages = usagesByPermission[permission].orEmpty(),
                declaredPermissions = declarationsByPermission.keys,
                sourceFiles = sourceFiles,
                graph = graph
            )
        }

        val components = manifests.flatMap { manifest ->
            manifest.components.map { component ->
                ComponentInfo(
                    name = component.name,
                    type = component.type,
                    exported = component.exported,
                    permission = component.permission,
                    hasIntentFilter = component.hasIntentFilter,
                    file = manifest.relativePath,
                    line = component.line,
                    module = manifest.module
                )
            }
        }

        val entryPoints = buildEntryPoints(permissionReports)
        val findings = FindingEngine(buildInfo, manifests, permissionReports, components, sourceFiles).run()
        val duration = System.currentTimeMillis() - startedAt

        val modules = sourceFiles.groupBy { it.module }.map { (module, files) ->
            ModuleInfo(
                name = module,
                relativePath = module,
                kotlinFileCount = files.count { it.language == "Kotlin" },
                javaFileCount = files.count { it.language == "Java" },
                dartFileCount = files.count { it.language == "Dart" },
                manifestCount = manifests.count { it.module == module }
            )
        }.sortedBy { it.name }

        val summary = buildSummary(permissionReports, findings, duration)

        return AnalysisReport(
            schemaVersion = "1.0",
            generatedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            project = ProjectInfo(
                path = projectRoot.absolutePath,
                name = projectRoot.name,
                applicationId = buildInfo.applicationId,
                minSdk = buildInfo.minSdk,
                targetSdk = buildInfo.targetSdk,
                compileSdk = buildInfo.compileSdk,
                modules = modules,
                manifestFiles = manifests.map { it.relativePath },
                dependencies = buildInfo.dependencies,
                kotlinFileCount = sourceFiles.count { it.language == "Kotlin" },
                javaFileCount = sourceFiles.count { it.language == "Java" },
                dartFileCount = sourceFiles.count { it.language == "Dart" },
                analyzedLineCount = sourceFiles.sumOf { it.rawLines.size }
            ),
            summary = summary,
            permissions = permissionReports.sortedWith(
                compareBy({ riskOrder(it.riskLevel) }, { it.permission })
            ),
            findings = findings.sortedBy { severityOrder(it.severity) },
            components = components,
            entryPoints = entryPoints
        )
    }

    private fun declarations(manifests: List<ParsedManifest>): Map<String, List<ManifestDeclaration>> =
        manifests.flatMap { manifest ->
            manifest.permissions.map { permission ->
                permission.name to ManifestDeclaration(
                    file = manifest.relativePath,
                    line = permission.line,
                    module = manifest.module,
                    maxSdkVersion = permission.maxSdkVersion,
                    sdk23Only = permission.sdk23Only,
                    snippet = permission.snippet
                )
            }
        }.groupBy({ it.first }, { it.second })

    private fun detectUsages(sourceFiles: List<SourceFile>, graph: CallGraph): Map<String, List<Usage>> {
        val result = mutableMapOf<String, MutableList<Usage>>()

        sourceFiles.forEach { file ->
            file.codeLines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val lineNumber = index + 1
                val enclosing = graph.declarationAt(file.file.path, lineNumber)?.qualifiedName ?: file.packageName

                PermissionCatalog.specs.forEach { spec ->
                    spec.signals.forEach { signal ->
                        val importSatisfied = signal.requiresAnyImport.isEmpty() ||
                            signal.requiresAnyImport.any { needle -> file.imports.any { it.contains(needle) } }
                        if (importSatisfied && signal.regex.containsMatchIn(line)) {
                            result.getOrPut(spec.name) { mutableListOf() }.add(
                                Usage(
                                    file = file.relativePath,
                                    line = lineNumber,
                                    module = file.module,
                                    enclosing = enclosing,
                                    symbol = signal.label,
                                    kind = UsageKind.API_CALL,
                                    evidence = signal.label,
                                    confidence = signal.confidence.name,
                                    snippet = file.rawLines.getOrElse(index) { "" }.trim().take(220)
                                )
                            )
                        }
                    }
                }

                if (file.language == "Dart") {
                    DART_PERMISSION_REGEX.findAll(line).forEach { match ->
                        val pluginName = match.groupValues[1]
                        DART_PERMISSION_MAP[pluginName]?.forEach { permission ->
                            val requested = line.contains(".request(") || line.contains("requestPermissions")
                            val checked = line.contains(".status") || line.contains("isGranted") ||
                                line.contains("isDenied") || line.contains(".isPermanentlyDenied")
                            result.getOrPut(permission) { mutableListOf() }.add(
                                Usage(
                                    file = file.relativePath,
                                    line = lineNumber,
                                    module = file.module,
                                    enclosing = enclosing,
                                    symbol = "Permission.$pluginName",
                                    kind = UsageKind.API_CALL,
                                    evidence = "permission_handler: Permission.$pluginName",
                                    confidence = Confidence.HIGH.name,
                                    snippet = file.rawLines.getOrElse(index) { "" }.trim().take(220)
                                )
                            )
                            if (requested || checked) {
                                result.getOrPut(permission) { mutableListOf() }.add(
                                    Usage(
                                        file = file.relativePath,
                                        line = lineNumber,
                                        module = file.module,
                                        enclosing = enclosing,
                                        symbol = "Permission.$pluginName",
                                        kind = if (requested) UsageKind.REQUEST else UsageKind.CHECK,
                                        evidence = if (requested) "permission_handler request" else "permission_handler status check",
                                        confidence = Confidence.HIGH.name,
                                        snippet = file.rawLines.getOrElse(index) { "" }.trim().take(220)
                                    )
                                )
                            }
                        }
                    }
                }

                val referenced = mutableSetOf<String>()
                MANIFEST_CONSTANT.findAll(line).forEach { referenced.add("android.permission." + it.groupValues[1]) }
                PERMISSION_LITERAL.findAll(line).forEach { referenced.add(it.groupValues[1]) }

                if (referenced.isNotEmpty()) {
                    val kind = classify(line) ?: classify(windowText(file.codeLines, index))
                        ?: UsageKind.PERMISSION_STRING
                    referenced.forEach { permission ->
                        result.getOrPut(permission) { mutableListOf() }.add(
                            Usage(
                                file = file.relativePath,
                                line = lineNumber,
                                module = file.module,
                                enclosing = enclosing,
                                symbol = permission.substringAfterLast('.'),
                                kind = kind,
                                evidence = when (kind) {
                                    UsageKind.REQUEST -> "Runtime permission request"
                                    UsageKind.CHECK -> "Permission state check"
                                    else -> "Permission constant reference"
                                },
                                confidence = Confidence.HIGH.name,
                                snippet = file.rawLines.getOrElse(index) { "" }.trim().take(220)
                            )
                        )
                    }
                }
            }
        }

        return result.mapValues { (_, usages) ->
            usages.distinctBy { "${it.file}:${it.line}:${it.kind}:${it.evidence}" }
        }
    }

    private fun classify(text: String): String? {
        val lowered = text.lowercase()
        if (REQUEST_HINTS.any { lowered.contains(it.lowercase()) }) return UsageKind.REQUEST
        if (CHECK_HINTS.any { lowered.contains(it.lowercase()) }) return UsageKind.CHECK
        return null
    }

    private fun windowText(codeLines: List<String>, index: Int): String {
        val from = (index - 4).coerceAtLeast(0)
        val to = (index + 4).coerceAtMost(codeLines.size - 1)
        return codeLines.subList(from, to + 1).joinToString(" ")
    }

    private fun buildPermissionReport(
        permission: String,
        declarations: List<ManifestDeclaration>,
        usages: List<Usage>,
        declaredPermissions: Set<String>,
        sourceFiles: List<SourceFile>,
        graph: CallGraph
    ): PermissionReport {
        val spec = PermissionCatalog.describe(permission)
        val apiUsages = usages.filter { it.kind == UsageKind.API_CALL }
        val requests = usages.filter { it.kind == UsageKind.REQUEST }
        val checks = usages.filter { it.kind == UsageKind.CHECK }
        val constants = usages.filter { it.kind == UsageKind.PERMISSION_STRING }

        val strongUsages = apiUsages.filter { it.confidence != Confidence.LOW.name }
        val alternativeCovered = ALTERNATIVE_GROUPS
            .filter { permission in it }
            .flatten()
            .any { it != permission && it in declaredPermissions }

        val status = when {
            declarations.isEmpty() && strongUsages.isEmpty() && requests.isEmpty() && apiUsages.isNotEmpty() ->
                PermissionStatus.WEAK_SIGNAL
            declarations.isEmpty() && alternativeCovered && requests.isEmpty() ->
                PermissionStatus.ALTERNATIVE_COVERED
            apiUsages.isNotEmpty() && declarations.isEmpty() -> PermissionStatus.UNDECLARED_USAGE
            apiUsages.isNotEmpty() -> PermissionStatus.USED
            (requests.isNotEmpty() || checks.isNotEmpty() || constants.isNotEmpty()) && declarations.isNotEmpty() ->
                PermissionStatus.REQUESTED_ONLY
            (requests.isNotEmpty() || checks.isNotEmpty()) && declarations.isEmpty() -> PermissionStatus.UNDECLARED_USAGE
            else -> PermissionStatus.DECLARED_UNUSED
        }

        val dangerous = spec.protection == Protection.DANGEROUS || spec.protection == Protection.SPECIAL
        val riskLevel = when {
            status == PermissionStatus.WEAK_SIGNAL || status == PermissionStatus.ALTERNATIVE_COVERED -> "LOW"
            status == PermissionStatus.UNDECLARED_USAGE -> "HIGH"
            status == PermissionStatus.DECLARED_UNUSED && dangerous -> "HIGH"
            status == PermissionStatus.DECLARED_UNUSED -> "MEDIUM"
            dangerous && requests.isEmpty() && spec.protection == Protection.DANGEROUS -> "HIGH"
            spec.policyNote != null -> "MEDIUM"
            status == PermissionStatus.REQUESTED_ONLY -> "MEDIUM"
            else -> "LOW"
        }

        val confidence = when {
            apiUsages.any { it.confidence == Confidence.HIGH.name } || requests.isNotEmpty() -> Confidence.HIGH.name
            apiUsages.any { it.confidence == Confidence.MEDIUM.name } -> Confidence.MEDIUM.name
            apiUsages.isEmpty() && usages.isEmpty() -> Confidence.HIGH.name
            else -> Confidence.LOW.name
        }

        val callPaths = buildCallPaths(apiUsages.ifEmpty { requests }, sourceFiles, graph)

        return PermissionReport(
            permission = permission,
            shortName = spec.shortName,
            protection = spec.protection,
            group = spec.group,
            description = spec.description,
            declared = declarations.isNotEmpty(),
            declarations = declarations,
            status = status,
            riskLevel = riskLevel,
            confidence = confidence,
            usages = apiUsages + constants,
            runtimeRequests = requests,
            permissionChecks = checks,
            callPaths = callPaths,
            touchedFiles = usages.map { it.file }.distinct().sorted(),
            policyNote = spec.policyNote
        )
    }

    private fun buildCallPaths(
        usages: List<Usage>,
        sourceFiles: List<SourceFile>,
        graph: CallGraph
    ): List<CallPath> {
        val byRelativePath = sourceFiles.associateBy { it.relativePath }
        val paths = mutableListOf<CallPath>()
        usages.take(20).forEach { usage ->
            val file = byRelativePath[usage.file] ?: return@forEach
            val declaration = graph.declarationAt(file.file.path, usage.line) ?: return@forEach
            graph.pathsTo(declaration).forEach { graphPath ->
                paths.add(
                    CallPath(
                        entryPoint = graphPath.entry.qualifiedName,
                        entryKind = graphPath.entryKind,
                        depth = graphPath.nodes.size,
                        nodes = graphPath.nodes.map { node ->
                            CallPathNode(
                                symbol = node.qualifiedName,
                                file = node.filePath
                                    .removePrefix(projectRoot.absolutePath)
                                    .replace('\\', '/')
                                    .trimStart('/'),
                                line = node.startLine
                            )
                        }
                    )
                )
            }
        }
        return paths.distinctBy { path -> path.nodes.joinToString { "${it.symbol}:${it.line}" } }.take(12)
    }

    private fun buildEntryPoints(permissions: List<PermissionReport>): List<EntryPointInfo> {
        val map = mutableMapOf<String, MutableSet<String>>()
        val meta = mutableMapOf<String, CallPathNode>()
        val kinds = mutableMapOf<String, String>()

        permissions.forEach { report ->
            report.callPaths.forEach { path ->
                val head = path.nodes.firstOrNull() ?: return@forEach
                map.getOrPut(path.entryPoint) { mutableSetOf() }.add(report.permission)
                meta[path.entryPoint] = head
                kinds[path.entryPoint] = path.entryKind
            }
        }

        return map.map { (symbol, permissions) ->
            val node = meta[symbol]
            EntryPointInfo(
                symbol = symbol,
                kind = kinds[symbol] ?: "UNKNOWN",
                file = node?.file ?: "",
                line = node?.line ?: 0,
                module = node?.file?.substringBefore("/src/") ?: "",
                reachedPermissions = permissions.sorted()
            )
        }.sortedByDescending { it.reachedPermissions.size }
    }

    private fun buildSummary(
        permissions: List<PermissionReport>,
        findings: List<Finding>,
        durationMs: Long
    ): Summary {
        val severityCounts = Severity.values().associate { severity ->
            severity.label to findings.count { it.severity == severity.label }
        }.filterValues { it > 0 }

        val rawScore = findings.sumOf { finding ->
            Severity.values().firstOrNull { it.label == finding.severity }?.weight ?: 0
        }
        val score = rawScore.coerceAtMost(100)
        val grade = when {
            score < 10 -> "A"
            score < 25 -> "B"
            score < 45 -> "C"
            score < 70 -> "D"
            else -> "F"
        }

        return Summary(
            declaredPermissionCount = permissions.count { it.declared },
            usedPermissionCount = permissions.count { it.status == PermissionStatus.USED },
            unusedPermissionCount = permissions.count { it.status == PermissionStatus.DECLARED_UNUSED },
            undeclaredPermissionCount = permissions.count { it.status == PermissionStatus.UNDECLARED_USAGE },
            dangerousPermissionCount = permissions.count { it.protection == Protection.DANGEROUS && it.declared },
            riskScore = score,
            riskGrade = grade,
            findingCountBySeverity = severityCounts,
            usageCountByPermission = permissions
                .filter { it.usages.isNotEmpty() }
                .associate { it.shortName to it.usages.size },
            analysisDurationMs = durationMs
        )
    }

    private fun riskOrder(risk: String): Int = when (risk) {
        "HIGH" -> 0
        "MEDIUM" -> 1
        else -> 2
    }

    private fun severityOrder(severity: String): Int = when (severity) {
        Severity.CRITICAL.label -> 0
        Severity.HIGH.label -> 1
        Severity.MEDIUM.label -> 2
        Severity.LOW.label -> 3
        else -> 4
    }
}

internal fun usageAt(file: SourceFile, line: Int, symbol: String, kind: String, evidence: String): Usage = Usage(
    file = file.relativePath,
    line = line,
    module = file.module,
    enclosing = "",
    symbol = symbol,
    kind = kind,
    evidence = evidence,
    confidence = Confidence.HIGH.name,
    snippet = file.rawLines.getOrElse(line - 1) { "" }.trim().take(220)
)

internal fun manifestUsage(manifest: ParsedManifest, line: Int, symbol: String, evidence: String): Usage = Usage(
    file = manifest.relativePath,
    line = line,
    module = manifest.module,
    enclosing = "AndroidManifest",
    symbol = symbol,
    kind = UsageKind.MANIFEST,
    evidence = evidence,
    confidence = Confidence.HIGH.name,
    snippet = runCatching { manifest.file.readLines().getOrElse(line - 1) { "" }.trim().take(220) }.getOrDefault("")
)

internal fun sourceLineCount(files: List<SourceFile>): Int = files.sumOf { it.codeLines.size }

internal fun stripCommentsForTest(lines: List<String>): List<String> = SourceIndexer.stripComments(lines)
