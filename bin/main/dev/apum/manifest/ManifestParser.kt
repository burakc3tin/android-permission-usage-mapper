package dev.apum.manifest

import java.io.File

data class ManifestPermission(
    val name: String,
    val maxSdkVersion: Int?,
    val sdk23Only: Boolean,
    val line: Int,
    val snippet: String
)

data class ManifestComponent(
    val name: String,
    val type: String,
    val exported: Boolean?,
    val permission: String?,
    val hasIntentFilter: Boolean,
    val line: Int
)

data class ManifestFeature(
    val name: String,
    val required: Boolean?,
    val line: Int
)

data class ParsedManifest(
    val file: File,
    val relativePath: String,
    val module: String,
    val packageName: String?,
    val permissions: List<ManifestPermission>,
    val components: List<ManifestComponent>,
    val features: List<ManifestFeature>,
    val usesCleartextTraffic: Boolean?,
    val queriesBlock: Boolean
)

private val PERMISSION_TAG = Regex("""<uses-permission(-sdk-23)?\b([^>]*)>""")
private val FEATURE_TAG = Regex("""<uses-feature\b([^>]*)>""")
private val COMPONENT_TAG = Regex("""<(activity-alias|activity|service|receiver|provider)\b([\s\S]*?)(/>|>)""")
private val ATTRIBUTE = Regex("""([\w:\-]+)\s*=\s*"([^"]*)"""")

class ManifestParser(private val projectRoot: File) {

    fun parseAll(): List<ParsedManifest> {
        val skipDirs = setOf("build", ".gradle", ".git", ".idea", "node_modules", "out", ".cxx")
        return projectRoot.walkTopDown()
            .onEnter { dir -> dir.name !in skipDirs }
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .mapNotNull { parse(it) }
            .toList()
    }

    fun parse(file: File): ParsedManifest? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val relativePath = file.absolutePath
            .removePrefix(projectRoot.absolutePath)
            .replace('\\', '/')
            .trimStart('/')
        val module = relativePath.substringBefore("/src/", relativePath.substringBefore('/')).ifEmpty { "root" }

        val permissions = PERMISSION_TAG.findAll(text).map { match ->
            val attributes = attributesOf(match.groupValues[2])
            ManifestPermission(
                name = attributes["android:name"].orEmpty(),
                maxSdkVersion = attributes["android:maxSdkVersion"]?.toIntOrNull(),
                sdk23Only = match.groupValues[1].isNotEmpty(),
                line = lineOf(text, match.range.first),
                snippet = match.value.trim()
            )
        }.filter { it.name.isNotBlank() }.toList()

        val features = FEATURE_TAG.findAll(text).map { match ->
            val attributes = attributesOf(match.groupValues[1])
            ManifestFeature(
                name = attributes["android:name"].orEmpty(),
                required = attributes["android:required"]?.toBooleanStrictOrNull(),
                line = lineOf(text, match.range.first)
            )
        }.filter { it.name.isNotBlank() }.toList()

        val components = COMPONENT_TAG.findAll(text).map { match ->
            val type = match.groupValues[1]
            val attributes = attributesOf(match.groupValues[2])
            val selfClosing = match.groupValues[3] == "/>"
            val body = if (selfClosing) "" else bodyOf(text, match.range.last + 1, type)
            ManifestComponent(
                name = attributes["android:name"].orEmpty(),
                type = type,
                exported = attributes["android:exported"]?.toBooleanStrictOrNull(),
                permission = attributes["android:permission"],
                hasIntentFilter = body.contains("<intent-filter"),
                line = lineOf(text, match.range.first)
            )
        }.filter { it.name.isNotBlank() }.toList()

        val applicationAttributes = Regex("""<application\b([^>]*)>""").find(text)
            ?.let { attributesOf(it.groupValues[1]) }
            .orEmpty()

        return ParsedManifest(
            file = file,
            relativePath = relativePath,
            module = module,
            packageName = Regex("""<manifest\b([\s\S]*?)>""").find(text)
                ?.let { attributesOf(it.groupValues[1])["package"] },
            permissions = permissions,
            components = components,
            features = features,
            usesCleartextTraffic = applicationAttributes["android:usesCleartextTraffic"]?.toBooleanStrictOrNull(),
            queriesBlock = text.contains("<queries")
        )
    }

    private fun attributesOf(raw: String): Map<String, String> =
        ATTRIBUTE.findAll(raw).associate { it.groupValues[1] to it.groupValues[2] }

    private fun bodyOf(text: String, from: Int, tag: String): String {
        val closing = text.indexOf("</$tag>", from)
        if (closing < 0) return ""
        return text.substring(from, closing)
    }

    private fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset.coerceAtMost(text.length)).count { it == '\n' } + 1
}
