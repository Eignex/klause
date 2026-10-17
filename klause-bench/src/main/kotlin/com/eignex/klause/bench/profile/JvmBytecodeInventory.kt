package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile

internal object JvmBytecodeInventory {
    private const val MAX_CLASS_BYTES = 16 * 1024 * 1024
    private const val MAX_CLASSES = 100_000
    private const val MAX_ENTRIES = 200_000

    fun inspect(inputs: List<JvmBytecodeInput>, emit: (JsonObject) -> Unit): JsonObject {
        JvmBytecodeInputs.verify(inputs)
        val counts = Counts()
        val execution = runCatching {
            for (input in inputs) {
                if (input.kind == "jars") jars(input, emit, counts) else image(input, emit, counts)
            }
            require(counts.classes > 0) { "Bytecode inventory has no classes" }
        }
        val preservation = runCatching { JvmBytecodeInputs.verify(inputs) }
        execution.exceptionOrNull()?.let { failure -> preservation.exceptionOrNull()?.let(failure::addSuppressed) }
        execution.getOrThrow()
        preservation.getOrThrow()
        return buildJsonObject {
            put("inputs", inputs.size)
            put("jars", counts.jars)
            put("entries", counts.entries)
            put("classes", counts.classes)
            put("classErrors", counts.errors)
            put("allInputBytesAuthenticatedBeforeAndAfter", true)
            put("allClassEntriesVisited", true)
            put("allClassMetadataAvailable", counts.errors == 0)
            put("runtimeImageProviderHelpersExecuted", inputs.any { it.kind == "runtime-image" })
            put("originalSdkLauncherInvoked", false)
            put("originalCliRebuiltOrExecuted", false)
            put("constantResolutionRequestedByBytecodeParser", false)
            put("classDefinitionRequestedByBytecodeParser", false)
            put("runtimeCalleeSelectionProven", false)
            put("loadedClassSelectionProven", false)
            put("generatedBodySelectionProven", false)
            put("fullSourcePhaseCoverageProven", false)
            put("compilerElapsedTimeProven", false)
            put("acceptedSavingsCredit", false)
        }
    }

    private fun jars(input: JvmBytecodeInput, emit: (JsonObject) -> Unit, counts: Counts) {
        for (name in input.files.keys.filter { it.endsWith(".jar") }.sorted()) {
            val file = File(input.root, name)
            JarFile(file, false).use { jar ->
                val entries = jar.entries().asSequence().take(MAX_ENTRIES + 1).toList()
                require(entries.size <= MAX_ENTRIES - counts.entries) { "Whole entry inventory exceeds bounds" }
                counts.jars++
                counts.entries += entries.size
                val duplicates = entries.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
                for ((index, entry) in entries.withIndex()) {
                    if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                    val bytes = if (entry.name in duplicates) {
                        null
                    } else {
                        jar.getInputStream(entry).use {
                        it.readNBytes(MAX_CLASS_BYTES + 1)
                    }
                    }
                    record(
                        input,
                        buildJsonObject {
                        put("jar", name)
                        put("jarSha256", input.files.getValue(name).sha256)
                        put("entryIndex", index)
                        put("entry", entry.name)
                    },
                        bytes, emit, counts
                    )
                }
            }
        }
    }

    private fun image(input: JvmBytecodeInput, emit: (JsonObject) -> Unit, counts: Counts) {
        withJvmRuntimeImage(File(input.root).toPath()) { image ->
            val modules = image.getPath("/modules")
            require(Files.isDirectory(modules))
            val declared = File(input.root, "release").readLines().single { it.startsWith("MODULES=") }
                .substringAfter('=').removeSurrounding("\"").split(' ')
            require(declared.isNotEmpty() && declared.none { it.isBlank() } && declared.toSet().size == declared.size)
            val found = Files.list(modules).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() }
            require(found == declared.toSet()) { "Runtime image modules differ from release" }
            for (module in declared) require(Files.isRegularFile(modules.resolve(module).resolve("module-info.class")))
            var classIndex = 0
            Files.walk(modules).use { paths ->
                for (path in paths.iterator()) {
                    if (!Files.isRegularFile(path)) continue
                    require(++counts.entries <= MAX_ENTRIES) { "Whole entry inventory exceeds bounds" }
                    if (!path.toString().endsWith(".class")) continue
                    val relative = modules.relativize(path)
                    require(relative.nameCount >= 2)
                    val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_CLASS_BYTES + 1) }
                    record(
                        input,
                        buildJsonObject {
                        put("module", relative.getName(0).toString())
                        put("entryIndex", classIndex++)
                        put("entry", relative.subpath(1, relative.nameCount).toString())
                    },
                        bytes, emit, counts
                    )
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun record(
        input: JvmBytecodeInput,
        origin: JsonObject,
        bytes: ByteArray?,
        emit: (JsonObject) -> Unit,
        counts: Counts,
    ) {
        require(++counts.classes <= MAX_CLASSES) { "Whole class inventory exceeds bounds" }
        require(bytes == null || bytes.size <= MAX_CLASS_BYTES) { "Class byte input exceeds bounds" }
        val parsed = if (bytes == null) {
            Result.failure(IllegalArgumentException("Ambiguous duplicate entry"))
        } else {
            try {
                Result.success(JvmBytecodeMetadataReader.read(bytes))
            } catch (failure: RuntimeException) {
                Result.failure(failure)
            }
        }
        if (parsed.isFailure) counts.errors++
        emit(
            buildJsonObject {
            put("record", "class")
            put("inputId", input.id)
            put("inputKind", input.kind)
            put("studySourceHeads", JsonArray(input.studySourceHeads.map(::JsonPrimitive)))
            put("origin", origin)
            put("classSha256", bytes?.let(::sha256))
            put("metadata", parsed.getOrNull()?.let(JvmBytecodeEncoding::encode) ?: JsonNull)
            put("errorType", parsed.exceptionOrNull()?.javaClass?.name)
            put("entryBytesAmbiguous", bytes == null)
        }
        )
    }

    private class Counts(var jars: Int = 0, var entries: Int = 0, var classes: Int = 0, var errors: Int = 0)
}
