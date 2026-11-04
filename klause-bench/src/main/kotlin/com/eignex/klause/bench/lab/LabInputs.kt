package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.bench.source.WorkspaceChecksums
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchInput
import com.eignex.lab.bench.BenchInputRoot
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat

internal object LabInputs {
    const val RUNTIME = "klause-bench/build/lab-runtime/minizinc"

    fun resolve(ref: ProblemRef, inputOnly: Boolean = false, plainDimacs: Boolean = false): List<BenchInput> {
        require(!plainDimacs || ref.format == Format.DIMACS) { "Plain input carriers require DIMACS" }
        return if (plainDimacs) materialized(ref, inputOnly, true) else indexed(ref) ?: materialized(ref, inputOnly)
    }

    /**
     * [ref]'s inputs from its collections' indexes, without their bytes, when the lab accepts declared hashes. The
     * lab fetches each by content hash for the case that needs it, and [verify] checks it before the solve. MiniZinc
     * models stay [materialized]: their include directories and dependency scan need real trees and content.
     */
    private fun indexed(ref: ProblemRef): List<BenchInput>? {
        if (!CorpusFetcher.declaredInputs || ref.format == Format.MINIZINC) return null
        return (listOf(ref.source to "model") + listOfNotNull(ref.data?.let { it to "data" })).map { (source, role) ->
            declared(source, role) ?: return null
        }.distinct()
    }

    private fun declared(source: ProblemSource, role: String): BenchInput? {
        val collection = when (source) {
            is ProblemSource.External -> source.collection
            is ProblemSource.ExternalIndexed -> source.collection
            else -> return null
        }
        val index = CorpusFetcher.knownIndex(collection)
        val entry = if (source is ProblemSource.External && index != null) {
            val alternate = if (source.relPath.endsWith(CorpusFiles.SUFFIX)) {
                CorpusFiles.plainPath(source.relPath)
            } else {
                source.relPath + CorpusFiles.SUFFIX
            }
            index.entry(source.relPath) ?: index.entry(alternate)
        } else {
            val view = CorpusFetcher.indexed(collection) ?: return null
            view.entry(CorpusFetcher.resolve(source) { view.root })
        } ?: return null
        val path = "${collection.id}/${entry.path}"
        // The materialized path stages such names under the source tree; keep that behaviour for them.
        if (path.any { it == ':' || it == '\\' || it.isISOControl() }) return null
        return BenchInput(path, BenchInputRoot.CORPUS, role, entry.sha256)
    }

    private fun materialized(
        ref: ProblemRef,
        inputOnly: Boolean,
        plainDimacs: Boolean = false,
    ): List<BenchInput> = buildList {
        val source = materializedSource(ref.source, ref.format)
        val model = if (plainDimacs) CorpusFiles.unpack(source.toFile(), keepPacked = true).toPath() else source
        add(input(model, "model"))
        ref.data?.let { add(input(materializedSource(it, ref.format), "data")) }
        if (ref.format == Format.MINIZINC) {
            val collectionIncludes = CorpusFetcher.includeDirs(ref.source).map { it.toPath() }
            val directories = (listOf(model.parent) + collectionIncludes).distinct()
            for (directory in directories) {
                add(input(directory, "include"))
            }
            val visited = HashSet<Path>()
            fun dependencies(file: Path) {
                val actual = file.toRealPath()
                if (!visited.add(actual)) return
                add(input(actual, "dependency"))
                for (included in includeNames(Files.readString(actual))) {
                    val name = Path.of(included)
                    require(!name.isAbsolute) { "MiniZinc includes must be relative: $name" }
                    val dependency = (listOf(actual.parent) + directories).map { it.resolve(name) }
                        .firstOrNull(Files::isRegularFile)
                    dependency?.let(::dependencies)
                }
            }
            dependencies(model)
            if (!inputOnly) {
                val runtime = CorpusFetcher.workspaceRoot().toPath().resolve(RUNTIME)
                require(Files.isExecutable(runtime.resolve("bin/minizinc"))) { "Run :klause-bench:prepareLabRuntime" }
                add(input(runtime, "compiler"))
            }
        }
    }.distinct()

    private fun materializedSource(source: ProblemSource, format: Format): Path {
        val file = CorpusFetcher.resolve(source)
        if (format == Format.MINIZINC) return file.toPath()
        val collection = when (source) {
            is ProblemSource.External -> source.collection
            is ProblemSource.ExternalIndexed -> source.collection
            else -> return file.toPath()
        }
        val index = CorpusFetcher.knownIndex(collection) ?: return file.toPath()
        val relative = file.relativeTo(File(CorpusFetcher.cacheRoot, collection.id)).invariantSeparatorsPath
        val entry = index.entry(relative) ?: index.entry(CorpusFiles.plainPath(relative)) ?: return file.toPath()
        if (entry.path.endsWith(CorpusFiles.SUFFIX)) return file.toPath()
        val materialized = if (entry.path == relative) file else {
            val plainPresent = File(CorpusFiles.plainPath(file.path)).isFile
            CorpusFiles.unpack(file, keepPacked = true).also {
                if (!plainPresent) CorpusFetcher.cache().recordSizesUnder(it)
            }
        }
        require(materialized.length() == entry.bytes && hash(materialized.toPath()) == entry.sha256) {
            "Input checksum mismatch: ${collection.id}/${entry.path}"
        }
        return materialized.toPath()
    }

    internal fun includeNames(source: String): List<String> = buildList {
        var index = 0
        var include = false
        while (index < source.length) {
            when {
                source[index].isWhitespace() -> index++

                source[index] == '%' -> index = source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length

                source.startsWith("/*", index) ->
                    index = source.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: source.length

                source[index] == '"' -> {
                    index++
                    val value = StringBuilder()
                    while (index < source.length && source[index] != '"') {
                        if (source[index] == '\\' && index + 1 < source.length) index++
                        value.append(source[index++])
                    }
                    if (index < source.length && include) add(value.toString())
                    if (index < source.length) index++
                    include = false
                }

                source[index].isLetter() || source[index] == '_' -> {
                    val start = index++
                    while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
                    include = source.substring(start, index) == "include"
                }

                else -> {
                    include = false
                    index++
                }
            }
        }
    }

    private fun input(path: Path, role: String): BenchInput {
        val actual = path.toRealPath()
        val source = CorpusFetcher.workspaceRoot().toPath().toRealPath()
        val root = if (actual.startsWith(source)) source else CorpusFetcher.cacheRoot.toPath().toRealPath()
        require(actual.startsWith(root)) { "Input outside source and corpus: $actual" }
        val relative = root.relativize(actual).toString()
        val sha256 = if (Files.isRegularFile(actual)) hash(actual) else null
        if (relative.any { it == ':' || it == '\\' || it.isISOControl() }) {
            requireNotNull(sha256) { "Input directory requires a portable name: $relative" }
            val name = actual.fileName.toString().map {
                if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-") it else '_'
            }.joinToString("").takeLast(160)
            val staged = source.resolve("klause-bench/build/lab-inputs/$sha256/input-$name")
            Files.createDirectories(staged.parent)
            require(staged.parent.toRealPath().startsWith(source)) { "Input staging escapes source" }
            if (!Files.isRegularFile(staged) || hash(staged) != sha256) {
                val pending = Files.createTempFile(staged.parent, ".input-", ".tmp")
                try {
                    Files.copy(actual, pending, REPLACE_EXISTING)
                    require(hash(pending) == sha256) { "Input changed while staging: $relative" }
                    Files.move(pending, staged, ATOMIC_MOVE, REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(pending)
                }
            }
            require(staged.toRealPath().startsWith(source)) { "Input staging escapes source" }
            return BenchInput(
                source.relativize(staged).toString().replace('\\', '/'),
                BenchInputRoot.SOURCE,
                role,
                sha256,
            )
        }
        return BenchInput(
            relative.replace('\\', '/'),
            if (root == source) BenchInputRoot.SOURCE else BenchInputRoot.CORPUS,
            role,
            sha256,
        )
    }

    fun path(input: BenchInput, context: BenchContext): Path {
        val root = requireNotNull(
            if (input.root == BenchInputRoot.SOURCE) context.worktree else context.corpusDirectory,
        ).toRealPath()
        return root.resolve(input.path).toRealPath().also { require(it.startsWith(root)) { "Input escapes its root" } }
    }

    fun verify(case: BenchCase, context: BenchContext, cacheDirectory: Path? = WorkspaceChecksums.directory()) {
        val root = context.worktree ?: context.corpusDirectory ?: context.outputDirectory
        WorkspaceChecksums(root, cacheDirectory).use { checksums ->
            case.inputs.forEachIndexed { index, input ->
                val file = path(input, context)
                input.sha256?.let {
                    require(
                        Files.isRegularFile(file) && checksums.canonicalChecksum(file) == it,
                    ) { "Input checksum mismatch: ${input.path}" }
                }
                case.attributes["inputTree.$index"]?.let { expected ->
                    val cache = System.getenv("LAB_WORKSPACE_CACHE")?.let { storage ->
                        Path.of(storage, "compiler-inputs")
                    }
                    val compiler = cache != null && input.root == BenchInputRoot.SOURCE && input.role == "compiler"
                    val matches = Files.isDirectory(file) && if (compiler) {
                        CompilerInputReceipts(requireNotNull(cache)).matches(file, expected)
                    } else {
                        treeHash(file, cacheDirectory) == expected
                    }
                    require(matches) { "Input tree changed: ${input.path}" }
                }
            }
        }
    }

    fun treeHashes(
        inputs: List<BenchInput>,
        compilerTrees: MutableMap<Path, String> = mutableMapOf(),
    ): Map<String, String> = buildMap {
        inputs.forEachIndexed { index, input ->
            val root = if (input.root == BenchInputRoot.SOURCE) {
                CorpusFetcher.workspaceRoot().toPath()
            } else {
                CorpusFetcher.cacheRoot.toPath()
            }
            val path = root.resolve(input.path)
            if (Files.isDirectory(path)) {
                val hash = if (input.root == BenchInputRoot.SOURCE && input.role == "compiler") {
                    compilerTrees.getOrPut(path.toRealPath()) { treeHash(path) }
                } else {
                    treeHash(path)
                }
                put("inputTree.$index", hash)
            }
        }
    }

    internal fun treeHash(directory: Path): String = treeHash(directory, WorkspaceChecksums.directory())

    internal fun treeHash(directory: Path, cacheDirectory: Path?): String =
        WorkspaceChecksums(directory, cacheDirectory).use { checksums ->
            val canonical = directory.toRealPath()
            val digest = MessageDigest.getInstance("SHA-256")
            Files.walk(directory).use { files ->
                files.filter(Files::isRegularFile).sorted().forEach { file ->
                    val actual = file.toRealPath()
                    require(actual.startsWith(canonical)) { "Input tree contains an escaping link" }
                    digest.update(directory.relativize(file).toString().replace('\\', '/').toByteArray())
                    digest.update(0.toByte())
                    digest.update(checksums.canonicalChecksum(actual).toByteArray())
                    digest.update(0.toByte())
                }
            }
            HexFormat.of().formatHex(digest.digest())
        }

    private fun hash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
