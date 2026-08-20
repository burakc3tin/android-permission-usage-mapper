package dev.apum.model

import kotlinx.serialization.Serializable

@Serializable
data class AnalysisReport(
    val schemaVersion: String,
    val generatedAt: String,
    val project: ProjectInfo,
    val summary: Summary,
    val permissions: List<PermissionReport>,
    val findings: List<Finding>,
    val components: List<ComponentInfo>,
    val entryPoints: List<EntryPointInfo>
)

@Serializable
data class ProjectInfo(
    val path: String,
    val name: String,
    val applicationId: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val compileSdk: Int?,
    val modules: List<ModuleInfo>,
    val manifestFiles: List<String>,
    val dependencies: List<String>,
    val kotlinFileCount: Int,
    val javaFileCount: Int,
    val dartFileCount: Int,
    val analyzedLineCount: Int
)

@Serializable
data class ModuleInfo(
    val name: String,
    val relativePath: String,
    val kotlinFileCount: Int,
    val javaFileCount: Int,
    val dartFileCount: Int,
    val manifestCount: Int
)

@Serializable
data class Summary(
    val declaredPermissionCount: Int,
    val usedPermissionCount: Int,
    val unusedPermissionCount: Int,
    val undeclaredPermissionCount: Int,
    val dangerousPermissionCount: Int,
    val riskScore: Int,
    val riskGrade: String,
    val findingCountBySeverity: Map<String, Int>,
    val usageCountByPermission: Map<String, Int>,
    val analysisDurationMs: Long
)

@Serializable
data class PermissionReport(
    val permission: String,
    val shortName: String,
    val protection: String,
    val group: String,
    val description: String,
    val declared: Boolean,
    val declarations: List<ManifestDeclaration>,
    val status: String,
    val riskLevel: String,
    val confidence: String,
    val usages: List<Usage>,
    val runtimeRequests: List<Usage>,
    val permissionChecks: List<Usage>,
    val callPaths: List<CallPath>,
    val touchedFiles: List<String>,
    val policyNote: String?
)

@Serializable
data class ManifestDeclaration(
    val file: String,
    val line: Int,
    val module: String,
    val maxSdkVersion: Int?,
    val sdk23Only: Boolean,
    val snippet: String
)

@Serializable
data class Usage(
    val file: String,
    val line: Int,
    val module: String,
    val enclosing: String,
    val symbol: String,
    val kind: String,
    val evidence: String,
    val confidence: String,
    val snippet: String
)

@Serializable
data class CallPath(
    val entryPoint: String,
    val entryKind: String,
    val depth: Int,
    val nodes: List<CallPathNode>
)

@Serializable
data class CallPathNode(
    val symbol: String,
    val file: String,
    val line: Int
)

@Serializable
data class Finding(
    val id: String,
    val rule: String,
    val severity: String,
    val title: String,
    val detail: String,
    val permission: String?,
    val recommendation: String,
    val locations: List<Usage>
)

@Serializable
data class ComponentInfo(
    val name: String,
    val type: String,
    val exported: Boolean?,
    val permission: String?,
    val hasIntentFilter: Boolean,
    val file: String,
    val line: Int,
    val module: String
)

@Serializable
data class EntryPointInfo(
    val symbol: String,
    val kind: String,
    val file: String,
    val line: Int,
    val module: String,
    val reachedPermissions: List<String>
)

enum class Severity(val label: String, val weight: Int) {
    CRITICAL("CRITICAL", 40),
    HIGH("HIGH", 20),
    MEDIUM("MEDIUM", 8),
    LOW("LOW", 3),
    INFO("INFO", 0)
}

enum class Confidence { HIGH, MEDIUM, LOW }

object PermissionStatus {
    const val USED = "USED"
    const val DECLARED_UNUSED = "DECLARED_UNUSED"
    const val UNDECLARED_USAGE = "UNDECLARED_USAGE"
    const val REQUESTED_ONLY = "REQUESTED_ONLY"
    const val LIBRARY_ONLY = "LIBRARY_ONLY"
    const val WEAK_SIGNAL = "WEAK_SIGNAL"
    const val ALTERNATIVE_COVERED = "ALTERNATIVE_COVERED"
}
