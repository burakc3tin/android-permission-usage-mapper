package dev.apum.source

import java.io.File

data class Declaration(
    val simpleName: String,
    val qualifiedName: String,
    val kind: String,
    val startLine: Int,
    val endLine: Int,
    val container: String?,
    val superTypes: List<String>,
    val annotations: List<String>,
    val filePath: String,
    val module: String
) {
    val id: String get() = "$filePath#$qualifiedName#$startLine"
}

data class SourceFile(
    val file: File,
    val relativePath: String,
    val module: String,
    val language: String,
    val packageName: String,
    val imports: List<String>,
    val rawLines: List<String>,
    val codeLines: List<String>,
    val declarations: List<Declaration>,
    val isTest: Boolean
)

object DeclarationKind {
    const val CLASS = "CLASS"
    const val OBJECT = "OBJECT"
    const val INTERFACE = "INTERFACE"
    const val FUNCTION = "FUNCTION"
    const val COMPOSABLE = "COMPOSABLE"
}

private val PACKAGE_REGEX = Regex("""^\s*package\s+([A-Za-z_][\w.]*)""")
private val IMPORT_REGEX = Regex("""^\s*import\s+(?:static\s+)?([A-Za-z_][\w.*]*)""")
private val TYPE_REGEX = Regex("""^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|private|protected|internal|abstract|final|open|sealed|data|inner|static|value|annotation)\s+)*(class|object|interface|enum\s+class)\s+([A-Za-z_]\w*)""")
private val KOTLIN_FUN_REGEX = Regex("""^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|private|protected|internal|inline|suspend|override|open|abstract|operator|external|tailrec|infix|final)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[A-Za-z_][\w.<>]*\s*\.\s*)?([A-Za-z_]\w*)\s*\(""")
private val JAVA_METHOD_REGEX = Regex("""^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|private|protected|static|final|synchronized|abstract|native|default)\s+)+[\w<>\[\],.\s?]+\s+([A-Za-z_]\w*)\s*\([^;{]*\)\s*(?:throws\s+[\w,.\s]+)?\{""")
private val SUPPORTED_EXTENSIONS = setOf("kt", "java", "dart")
private val DART_FUNCTION_REGEX = Regex("""^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:static\s+)?(?:Future\s*<[^>]*>|Stream\s*<[^>]*>|void|bool|int|double|num|String|dynamic|var|[A-Z]\w*(?:<[^>]*>)?)\s+([a-zA-Z_]\w*)\s*\([^;]*\)\s*(?:async\*?\s*)?\{""")
private val CALL_REGEX =Regex("""(?:([A-Za-z_][\w.]*)\s*\.\s*)?([a-zA-Z_]\w*)\s*\(""")
private val ANNOTATION_REGEX = Regex("""@([A-Za-z_]\w*)""")

class SourceIndexer(private val projectRoot: File, private val includeTests: Boolean) {

    fun index(): List<SourceFile> {
        val files = collectSourceFiles()
        return files.mapNotNull { parse(it) }.filter { includeTests || !it.isTest }
    }

    private fun collectSourceFiles(): List<File> {
        val skipDirs = setOf("build", ".gradle", ".git", ".idea", "node_modules", "out", "generated", ".cxx")
        val result = mutableListOf<File>()
        projectRoot.walkTopDown()
            .onEnter { dir -> dir.name !in skipDirs && !dir.name.startsWith(".") || dir == projectRoot }
            .forEach { candidate ->
                if (candidate.isFile && candidate.extension in SUPPORTED_EXTENSIONS) {
                    result.add(candidate)
                }
            }
        return result
    }

    private fun parse(file: File): SourceFile? {
        val rawLines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (rawLines.isEmpty()) return null
        val codeLines = stripComments(rawLines)
        val relativePath = relativize(file)
        val module = moduleOf(relativePath)
        val packageName = codeLines.firstNotNullOfOrNull { PACKAGE_REGEX.find(it)?.groupValues?.get(1) } ?: ""
        val imports = codeLines.mapNotNull { IMPORT_REGEX.find(it)?.groupValues?.get(1) }
        val isTest = relativePath.contains("/test/") || relativePath.contains("/androidTest/") ||
            relativePath.endsWith("Test.kt") || relativePath.endsWith("Test.java")
        val declarations = parseDeclarations(codeLines, file.path, module, packageName)
        return SourceFile(
            file = file,
            relativePath = relativePath,
            module = module,
            language = when (file.extension) { "kt" -> "Kotlin"; "dart" -> "Dart"; else -> "Java" },
            packageName = packageName,
            imports = imports,
            rawLines = rawLines,
            codeLines = codeLines,
            declarations = declarations,
            isTest = isTest
        )
    }

    private fun relativize(file: File): String =
        file.absolutePath.removePrefix(projectRoot.absolutePath).replace('\\', '/').trimStart('/')

    private fun moduleOf(relativePath: String): String {
        val marker = relativePath.indexOf("/src/")
        if (marker <= 0) return relativePath.substringBefore('/').ifEmpty { "root" }
        return relativePath.substring(0, marker).ifEmpty { "root" }
    }

    private fun parseDeclarations(
        codeLines: List<String>,
        filePath: String,
        module: String,
        packageName: String
    ): List<Declaration> {
        val types = mutableListOf<Declaration>()
        val functions = mutableListOf<Declaration>()

        codeLines.forEachIndexed { index, line ->
            val typeMatch = TYPE_REGEX.find(line)
            if (typeMatch != null) {
                val keyword = typeMatch.groupValues[1]
                val name = typeMatch.groupValues[2]
                val end = blockEnd(codeLines, index)
                val kind = when {
                    keyword.startsWith("object") -> DeclarationKind.OBJECT
                    keyword.startsWith("interface") -> DeclarationKind.INTERFACE
                    else -> DeclarationKind.CLASS
                }
                types.add(
                    Declaration(
                        simpleName = name,
                        qualifiedName = if (packageName.isEmpty()) name else "$packageName.$name",
                        kind = kind,
                        startLine = index + 1,
                        endLine = end + 1,
                        container = null,
                        superTypes = superTypesOf(codeLines, index),
                        annotations = annotationsAbove(codeLines, index),
                        filePath = filePath,
                        module = module
                    )
                )
            }
        }

        codeLines.forEachIndexed { index, line ->
            val name = KOTLIN_FUN_REGEX.find(line)?.groupValues?.get(1)
                ?: JAVA_METHOD_REGEX.find(line)?.groupValues?.get(1)
                ?: DART_FUNCTION_REGEX.find(line)?.groupValues?.get(1)
            if (name != null && name !in setOf("if", "for", "while", "switch", "catch", "return", "new")) {
                val end = blockEnd(codeLines, index)
                val annotations = annotationsAbove(codeLines, index) + ANNOTATION_REGEX.findAll(line).map { it.groupValues[1] }
                val owner = types
                    .filter { index + 1 in it.startLine..it.endLine }
                    .maxByOrNull { it.startLine }
                functions.add(
                    Declaration(
                        simpleName = name,
                        qualifiedName = listOfNotNull(owner?.qualifiedName ?: packageName.ifEmpty { null }, name)
                            .joinToString("."),
                        kind = if (annotations.contains("Composable")) DeclarationKind.COMPOSABLE else DeclarationKind.FUNCTION,
                        startLine = index + 1,
                        endLine = end + 1,
                        container = owner?.qualifiedName,
                        superTypes = emptyList(),
                        annotations = annotations.distinct(),
                        filePath = filePath,
                        module = module
                    )
                )
            }
        }

        return (types + functions).sortedBy { it.startLine }
    }

    private fun superTypesOf(codeLines: List<String>, declarationIndex: Int): List<String> {
        val builder = StringBuilder()
        var cursor = declarationIndex
        while (cursor < codeLines.size && cursor < declarationIndex + 5) {
            builder.append(codeLines[cursor])
            if (codeLines[cursor].contains('{')) break
            cursor++
        }
        val header = builder.toString()
        val kotlinPart = header.substringAfter(':', "").substringBefore('{')
        val javaPart = Regex("""(?:extends|implements)\s+([\w<>,.\s]+)""").findAll(header)
            .joinToString(",") { it.groupValues[1] }
        return Regex("""[A-Za-z_][\w.]*""")
            .findAll("$kotlinPart,$javaPart")
            .map { it.value.substringAfterLast('.') }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun annotationsAbove(codeLines: List<String>, declarationIndex: Int): List<String> {
        val result = mutableListOf<String>()
        var cursor = declarationIndex - 1
        while (cursor >= 0) {
            val trimmed = codeLines[cursor].trim()
            if (trimmed.startsWith("@")) {
                result += ANNOTATION_REGEX.findAll(trimmed).map { it.groupValues[1] }
                cursor--
            } else if (trimmed.isEmpty()) {
                cursor--
            } else {
                break
            }
        }
        return result
    }

    private fun blockEnd(codeLines: List<String>, startIndex: Int): Int {
        var depth = 0
        var seenBrace = false
        var cursor = startIndex
        while (cursor < codeLines.size) {
            for (character in codeLines[cursor]) {
                if (character == '{') {
                    depth++
                    seenBrace = true
                } else if (character == '}') {
                    depth--
                    if (seenBrace && depth <= 0) return cursor
                }
            }
            if (!seenBrace && cursor > startIndex + 3) return startIndex
            cursor++
        }
        return minOf(codeLines.size - 1, startIndex)
    }

    companion object {
        fun callsIn(codeLines: List<String>, fromLine: Int, toLine: Int): List<Pair<String, String?>> {
            val result = mutableListOf<Pair<String, String?>>()
            for (index in (fromLine - 1).coerceAtLeast(0) until minOf(toLine, codeLines.size)) {
                CALL_REGEX.findAll(codeLines[index]).forEach { match ->
                    val receiver = match.groupValues[1].takeIf { it.isNotBlank() }
                    val callee = match.groupValues[2]
                    if (callee !in KEYWORDS) result.add(callee to receiver)
                }
            }
            return result
        }

        private val KEYWORDS = setOf(
            "if", "for", "while", "switch", "catch", "return", "when", "synchronized",
            "super", "this", "new", "fun", "val", "var", "println", "require", "check"
        )

        fun stripComments(lines: List<String>): List<String> {
            val result = ArrayList<String>(lines.size)
            var inBlockComment = false
            for (line in lines) {
                val builder = StringBuilder()
                var index = 0
                var inString = false
                var stringChar = ' '
                while (index < line.length) {
                    val current = line[index]
                    val next = if (index + 1 < line.length) line[index + 1] else ' '
                    when {
                        inBlockComment -> {
                            if (current == '*' && next == '/') {
                                inBlockComment = false
                                index++
                            }
                        }
                        inString -> {
                            builder.append(current)
                            if (current == '\\') {
                                if (index + 1 < line.length) builder.append(next)
                                index++
                            } else if (current == stringChar) {
                                inString = false
                            }
                        }
                        current == '/' && next == '/' -> index = line.length
                        current == '/' && next == '*' -> {
                            inBlockComment = true
                            index++
                        }
                        current == '"' || current == '\'' -> {
                            inString = true
                            stringChar = current
                            builder.append(current)
                        }
                        else -> builder.append(current)
                    }
                    index++
                }
                result.add(builder.toString())
            }
            return result
        }
    }
}
