package com.eignex.klause.bench.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

internal object JvmBytecodeInputs {
    private const val MAX_FILES = 4096
    private const val MAX_FILE_BYTES = 512L * 1024 * 1024
    private const val MAX_INPUT_BYTES = 2L * 1024 * 1024 * 1024
    private val hash = Regex("[0-9a-f]{64}")

    @Suppress("CyclomaticComplexMethod")
    fun read(text: String): List<JvmBytecodeInput> {
        rejectDuplicateKeys(text)
        val specification = Json.decodeFromString(JvmBytecodeSpecification.serializer(), text)
        require(specification.schema == 1 && specification.inputs.size in 1..8)
        val ids = mutableSetOf<String>()
        val roots = mutableSetOf<String>()
        for (input in specification.inputs) {
            require(input.id.length in 1..4096 && input.id.none(Char::isISOControl) && ids.add(input.id))
            require(input.kind in setOf("jars", "runtime-image"))
            require(input.studySourceHeads.size in 1..16)
            require(input.studySourceHeads.toSet().size == input.studySourceHeads.size)
            require(input.studySourceHeads.all { it.matches(Regex("[0-9a-f]{40}")) })
            require(input.root.length in 1..4096 && input.root.none(Char::isISOControl))
            val root = File(input.root)
            require(root.isAbsolute && root.normalize().path == root.path && roots.add(root.path))
            require(input.files.size in 1..MAX_FILES)
            for ((path, identity) in input.files) {
                require(path.length in 1..4096 && !path.startsWith('/') && '\\' !in path && ':' !in path)
                require(path.none(Char::isISOControl) && path.split('/').none { it in setOf("", ".", "..") })
                require(identity.bytes in 0..MAX_FILE_BYTES && hash.matches(identity.sha256))
            }
            if (input.kind == "runtime-image") {
                require(input.files.keys.containsAll(listOf("lib/modules", "lib/jrt-fs.jar", "release")))
            } else {
                require(input.files.keys.any { it.endsWith(".jar") })
            }
        }
        require(specification.inputs.sumOf { it.files.size } <= MAX_FILES)
        require(specification.inputs.sumOf { input -> input.files.values.sumOf { it.bytes } } <= MAX_INPUT_BYTES)
        return specification.inputs
    }

    fun verify(inputs: List<JvmBytecodeInput>) {
        for (input in inputs) {
            val root = File(input.root)
            require(root.canonicalFile == root.absoluteFile && root.isDirectory) { "Unsafe bytecode input root" }
            val found = mutableSetOf<String>()
            var entries = 0
            Files.walk(root.toPath()).use { paths ->
                for (path in paths.iterator()) {
                    require(++entries <= 200_000) { "Bytecode input path inventory exceeds bounds" }
                    require(!Files.isSymbolicLink(path)) { "Symbolic link in bytecode inputs" }
                    if (Files.isDirectory(path)) continue
                    require(Files.isRegularFile(path)) { "Special file in bytecode inputs" }
                    val relative = root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                    val expected = requireNotNull(input.files[relative]) { "Unlisted bytecode input file" }
                    require(found.add(relative) && path.toFile().length() == expected.bytes)
                    require(digest(path.toFile()) == expected.sha256) { "Bytecode input hash mismatch" }
                }
            }
            require(found == input.files.keys) { "Missing bytecode input file" }
        }
    }

    fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun rejectDuplicateKeys(text: String) {
        val frames = ArrayDeque<BytecodeJsonKeys?>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{' -> frames.addLast(BytecodeJsonKeys())

                '[' -> frames.addLast(null)

                '}', ']' -> if (frames.isNotEmpty()) frames.removeLast()

                ',' -> frames.lastOrNull()?.expectKey = true

                '"' -> {
                    val start = index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index++
                        index++
                    }
                    require(index < text.length)
                    val frame = frames.lastOrNull()
                    if (frame != null && frame.expectKey) {
                        val key = Json.parseToJsonElement(text.substring(start, index + 1)).jsonPrimitive.content
                        require(frame.keys.add(key)) { "Duplicate bytecode input key" }
                        frame.expectKey = false
                    }
                }
            }
            require(frames.size <= 64) { "Bytecode input nesting exceeds bounds" }
            index++
        }
    }
}

@Serializable
internal data class JvmBytecodeSpecification(val schema: Int, val inputs: List<JvmBytecodeInput>)

@Serializable
internal data class JvmBytecodeInput(
    val id: String,
    val kind: String,
    val studySourceHeads: List<String>,
    val root: String,
    val files: Map<String, CaptureFile>,
)

private class BytecodeJsonKeys(val keys: HashSet<String> = HashSet(), var expectKey: Boolean = true)
