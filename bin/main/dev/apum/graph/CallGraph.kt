package dev.apum.graph

import dev.apum.source.Declaration
import dev.apum.source.DeclarationKind
import dev.apum.source.SourceFile
import dev.apum.source.SourceIndexer

data class GraphPath(
    val entry: Declaration,
    val entryKind: String,
    val nodes: List<Declaration>
)

private val ENTRY_SUPER_TYPES = setOf(
    "Activity", "AppCompatActivity", "ComponentActivity", "FragmentActivity",
    "Fragment", "DialogFragment", "BottomSheetDialogFragment",
    "Service", "IntentService", "LifecycleService", "FirebaseMessagingService", "TileService",
    "BroadcastReceiver", "ContentProvider", "Application",
    "Worker", "CoroutineWorker", "ListenableWorker", "JobService", "JobIntentService"
)

private val ENTRY_FUNCTION_NAMES = setOf(
    "onCreate", "onStart", "onResume", "onStartCommand", "onReceive", "onBind",
    "doWork", "onHandleWork", "onHandleIntent", "onMessageReceived", "main", "onViewCreated"
)

class CallGraph(files: List<SourceFile>) {

    private val declarations: List<Declaration> = files.flatMap { it.declarations }
    private val functions: List<Declaration> =
        declarations.filter { it.kind == DeclarationKind.FUNCTION || it.kind == DeclarationKind.COMPOSABLE }
    private val typesByQualifiedName: Map<String, Declaration> =
        declarations.filter { it.kind != DeclarationKind.FUNCTION && it.kind != DeclarationKind.COMPOSABLE }
            .associateBy { it.qualifiedName }
    private val functionsBySimpleName: Map<String, List<Declaration>> = functions.groupBy { it.simpleName }
    private val callersOf: MutableMap<String, MutableSet<String>> = mutableMapOf()
    private val byId: Map<String, Declaration> = declarations.associateBy { it.id }

    init {
        files.forEach { file ->
            file.declarations
                .filter { it.kind == DeclarationKind.FUNCTION || it.kind == DeclarationKind.COMPOSABLE }
                .forEach { caller ->
                    SourceIndexer.callsIn(file.codeLines, caller.startLine + 1, caller.endLine)
                        .asSequence()
                        .map { it.first }
                        .distinct()
                        .forEach { calleeName ->
                            val candidates = functionsBySimpleName[calleeName].orEmpty()
                            if (candidates.isNotEmpty() && candidates.size <= 6) {
                                candidates.forEach { callee ->
                                    if (callee.id != caller.id) {
                                        callersOf.getOrPut(callee.id) { mutableSetOf() }.add(caller.id)
                                    }
                                }
                            }
                        }
                }
        }
    }

    fun declarationAt(filePath: String, line: Int): Declaration? =
        declarations
            .filter { it.filePath == filePath && line in it.startLine..it.endLine }
            .filter { it.kind == DeclarationKind.FUNCTION || it.kind == DeclarationKind.COMPOSABLE }
            .maxByOrNull { it.startLine }
            ?: declarations
                .filter { it.filePath == filePath && line in it.startLine..it.endLine }
                .maxByOrNull { it.startLine }

    fun entryKindOf(declaration: Declaration): String? {
        if (declaration.kind == DeclarationKind.COMPOSABLE) return "COMPOSABLE"
        val owner = declaration.container?.let { typesByQualifiedName[it] }
        val superTypes = owner?.superTypes.orEmpty()
        val matchedSuper = superTypes.firstOrNull { it in ENTRY_SUPER_TYPES }
        if (matchedSuper != null && declaration.simpleName in ENTRY_FUNCTION_NAMES) return matchedSuper
        if (matchedSuper != null) return matchedSuper
        if (declaration.simpleName in ENTRY_FUNCTION_NAMES) return "LIFECYCLE"
        return null
    }

    fun pathsTo(target: Declaration, maxPaths: Int = 5, maxDepth: Int = 8): List<GraphPath> {
        val results = mutableListOf<GraphPath>()
        val visited = mutableSetOf(target.id)
        val queue = ArrayDeque<List<Declaration>>()
        queue.add(listOf(target))

        while (queue.isNotEmpty() && results.size < maxPaths) {
            val path = queue.removeFirst()
            val head = path.first()
            val kind = entryKindOf(head)
            val callers = callersOf[head.id].orEmpty().mapNotNull { byId[it] }

            if (kind != null || callers.isEmpty()) {
                results.add(GraphPath(head, kind ?: "UNKNOWN", path))
                if (kind != null) continue
            }
            if (path.size >= maxDepth) continue

            callers.forEach { caller ->
                if (visited.add(caller.id)) {
                    queue.add(listOf(caller) + path)
                }
            }
        }
        return results.distinctBy { it.nodes.joinToString { node -> node.id } }
    }

    fun entryPoints(): List<Declaration> = functions.filter { entryKindOf(it) != null }
}
