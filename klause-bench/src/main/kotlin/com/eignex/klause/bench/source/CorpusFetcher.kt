package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.ExternalCollection
import com.eignex.klause.bench.catalog.FetchMethod
import com.eignex.klause.bench.catalog.ProblemSource
import org.tukaani.xz.LZMAInputStream
import java.io.File
import java.lang.Boolean.getBoolean
import java.net.URI
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipFile

// A resolved file may be stored zstd-compressed as `<name>.<ext>.zst`; read it through CorpusFiles, never directly.
internal object CorpusFetcher {

    private val DECOMPRESS_TOOLS = mapOf("xz" to "unxz", "bz2" to "bunzip2", "gz" to "gunzip")

    fun workspaceRoot(): File {
        System.getProperty("klause.workspace.root")?.let { return File(it) }
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            if (File(cur, "klause-mzn-lib").isDirectory) return cur
            cur = cur.parentFile
        }
        error("could not locate workspace root (no klause-mzn-lib parent); set -Dklause.workspace.root")
    }

    // Outside the repo tree so multi-GB fetches survive `git clean` and are shared across worktrees.
    val cacheRoot: File
        get() = System.getProperty("klause.bench.corpusCache")?.let { File(it) }
            ?: defaultCacheRoot()

    private fun defaultCacheRoot(): File = File(xdgCacheHome(), "klause-bench/corpus")

    private val storageRoot: File
        get() = if (getBoolean("klause.bench.corpus.shared")) defaultCacheRoot() else cacheRoot

    private val downloads = CorpusDownload()
    private val zipCollections = mutableMapOf<ExternalCollection, CorpusZipCollection>()
    private val mirror: CorpusMirror?
        get() {
            val address = System.getenv("LAB_CORPUS_MIRROR") ?: System.getenv("KLAUSE_BENCH_CORPUS_MIRROR")
            return address?.takeIf { it.isNotBlank() }?.let { CorpusMirror(URI(it)) }
        }

    private fun xdgCacheHome(): File = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".cache")

    // Set when the lab publishes inputs by content hash and verifies every fetched byte; only then may a plan select
    // from a CorpusIndex without materializing the collection.
    val declaredInputs: Boolean get() = System.getProperty("klause.bench.declaredInputs") == "true"

    private val indexes: CorpusIndexes get() = CorpusIndexes(storageRoot)

    fun knownIndex(collection: ExternalCollection): CorpusIndex? =
        CommittedCorpusIndexes.load(collection.id) ?: indexes.local(collection.id)

    fun declaredPaths(collection: ExternalCollection): List<String>? =
        if (declaredInputs) knownIndex(collection)?.files?.map { it.path } else null

    // Only for declared inputs with indexed members absent locally; fetches a missing index from the mirror but never
    // the pack itself.
    fun indexed(collection: ExternalCollection): IndexedCollection? {
        if (!declaredInputs) return null
        // A committed index keeps planning offline; the mirror is only a fallback for collections without one.
        val index = knownIndex(collection) ?: mirror?.let { remote ->
            storageRoot.mkdirs()
            runCatching { remote.index(collection.id, storageRoot) }.onFailure {
                System.err.println("corpus index of '${collection.id}' unavailable: ${it.javaClass.simpleName}")
            }.getOrNull()?.also(indexes::save)
        } ?: return null
        val local = File(cacheRoot, collection.id)
        if (local.isDirectory && !File(local, CorpusStore.SELECTIVE).exists() &&
            index.files.all { File(local, it.path).isFile }
        ) {
            return null
        }
        return indexes.view(index)
    }

    fun listing(collection: ExternalCollection): File = indexed(collection)?.root ?: ensure(collection)

    fun selectivePaths(collection: ExternalCollection): List<String>? =
        if (collection.fetch is FetchMethod.SelectiveZip && indexed(collection) == null) {
            selectiveZip(collection)?.paths
        } else {
            null
        }

    private fun selectiveZip(collection: ExternalCollection): CorpusZipCollection? {
        val method = collection.fetch as? FetchMethod.SelectiveZip ?: return null
        if (present(collection.id)) return null
        return zipCollections.getOrPut(collection) {
            CorpusZipCollection(
                URI(collection.url),
                method.extension,
                requireNotNull(collection.maxFileMb).toLong() * 1024 * 1024,
                downloads,
            )
        }
    }

    private fun present(id: String): Boolean = listOf(storageRoot, cacheRoot).any { root ->
        File(root, id).let {
            it.isDirectory && !File(it, CorpusStore.SELECTIVE).exists() && it.list()?.isNotEmpty() == true
        }
    }

    // An index listing root resolves the same relative path without the collection's bytes.
    fun resolve(source: ProblemSource, root: ((ExternalCollection) -> File)? = null): File = when (source) {
        is ProblemSource.Prepared -> source.file.also { require(it.isFile) }

        is ProblemSource.Vendored -> File(workspaceRoot(), source.workspaceRelPath).also {
            require(it.isFile) { "vendored file missing: ${source.workspaceRelPath}" }
        }

        is ProblemSource.External -> (
            if (root == null && selectiveZip(source.collection) != null) {
                member(source.collection, source.relPath)
            } else {
                stored(File((root ?: ::ensure)(source.collection), source.relPath))
            }
            ).also {
            require(it.isFile) { "instance '${source.relPath}' not found in collection '${source.collection.id}'" }
        }

        is ProblemSource.ExternalIndexed -> {
            val files = (root ?: ::ensure)(source.collection).walkTopDown()
                .filter { it.isFile && CorpusFiles.formatExtension(it) == source.ext }
                .sortedWith(compareBy<File> { CorpusFiles.plainPath(it.name) }.thenBy { !CorpusFiles.isCompressed(it) })
                .distinctBy { CorpusFiles.plainPath(it.path) }
                .toList()
            require(source.index in files.indices) {
                "collection '${source.collection.id}' has ${files.size} *.${source.ext} files; " +
                    "index ${source.index} out of range"
            }
            files[source.index]
        }

        is ProblemSource.MpsFeasibility -> MpsFeasibility.resolve(resolve(source.source))

        is ProblemSource.InCode -> error("InCode sources have no file")
    }

    fun includeDirs(source: ProblemSource): List<File> = if (source is ProblemSource.Prepared) {
        source.includes
    } else if (source is ProblemSource.External && source.collection.includeDirs.isNotEmpty()) {
        val root = ensure(source.collection)
        source.collection.includeDirs.map { File(root, it) }.filter { it.isDirectory }
    } else {
        emptyList()
    }

    private fun mirrorWrite(): Boolean =
        (System.getenv("LAB_CORPUS_MIRROR_WRITE") ?: System.getenv("KLAUSE_BENCH_CORPUS_MIRROR_WRITE")) == "true"

    // Catalog paths may name either the plain or the compressed form.
    private fun stored(file: File): File {
        if (file.isFile) return file
        val counterpart = if (CorpusFiles.isCompressed(file)) {
            File(CorpusFiles.plainPath(file.path))
        } else {
            File(file.path + CorpusFiles.SUFFIX)
        }
        return if (counterpart.isFile) counterpart else file
    }

    // The cache cap never evicts collections resolved in this JVM.
    private val usedThisRun: MutableSet<String> = HashSet()

    fun cache(): CorpusCache = CorpusCache(storageRoot, CorpusCache.configuredCapBytes(), usedThisRun, log = ::log)

    // A fetch evicts least recently used collections to fit the cache cap, before and after it lands.
    fun ensure(collection: ExternalCollection): File {
        selectiveZip(collection)?.let { zip ->
            zip.paths.forEach { member(collection, it) }
            return File(cacheRoot, collection.id)
        }
        val cache = cache()
        cache.markUsed(collection.id)
        var fetched = false
        var packed = false
        val remote = mirror
        val directory = CorpusStore(storageRoot, cacheRoot).ensure(collection.id) { dir ->
            if (remote?.restore(collection.id, dir) == true) {
                fetched = true
                packed = true
                return@ensure
            }
            cache.enforce()
            log("fetching '${collection.id}' (${collection.license}) — ${collection.reason}")
            when (val method = collection.fetch) {
                is FetchMethod.GitClone -> gitClone(collection, method, dir)

                FetchMethod.Tarball -> tarball(collection, dir)

                FetchMethod.Tar -> tar(collection, dir)

                FetchMethod.TarballZst -> tarballZst(collection, dir)

                FetchMethod.Zip -> zip(collection, dir)

                is FetchMethod.SelectiveZip -> zip(collection, dir)

                is FetchMethod.ZipMember -> {
                    CorpusZipSelection(downloads).fetch(URI(collection.url), method, dir)
                    compress(dir)
                }

                is FetchMethod.Files -> files(collection, method, dir)

                is FetchMethod.GbdFiles -> gbdFiles(collection, method, dir)
            }
            collection.extensionless?.let { (marker, ext) -> nameBare(dir, marker, ext) }
            if (remote != null && mirrorWrite()) {
                runCatching { packed = remote.publish(collection.id, dir) }.onFailure {
                    System.err.println("corpus mirror publication deferred: ${it.javaClass.simpleName}")
                }
            }
            fetched = true
        }
        // Only a tree that is the stored pack may be indexed: a pack another fetch published first can differ.
        if (packed && indexes.local(collection.id) == null) {
            val index = CorpusIndex.of(collection.id, directory)
            indexes.save(index)
            if (remote != null && mirrorWrite()) {
                runCatching { remote.publishIndex(index, storageRoot) }.onFailure {
                    System.err.println("corpus index publication deferred: ${it.javaClass.simpleName}")
                }
            }
        }
        if (fetched) {
            cache.recordSize(collection.id)
            cache.enforce()
        }
        return directory
    }

    private fun member(collection: ExternalCollection, relative: String): File {
        val zip = requireNotNull(selectiveZip(collection))
        val cache = cache()
        cache.markUsed(collection.id)
        var fetched = false
        val file = CorpusStore(storageRoot, cacheRoot).member(collection.id, relative) { directory ->
            cache.enforce()
            zip.fetch(relative, directory).also { fetched = true }
        }
        if (fetched) {
            cache.recordSize(collection.id)
            cache.enforce()
        }
        return file
    }

    private fun gitClone(c: ExternalCollection, m: FetchMethod.GitClone, dir: File) {
        if (m.sparsePath != null) {
            run(
                "git",
                "clone",
                "--filter=blob:none",
                "--no-checkout",
                "--depth",
                m.depth.toString(),
                c.url,
                dir.absolutePath,
            )
            run(dir, "git", "sparse-checkout", "set", m.sparsePath)
            run(dir, "git", "checkout")
        } else {
            run("git", "clone", "--depth", m.depth.toString(), c.url, dir.absolutePath)
        }
    }

    private fun tarball(c: ExternalCollection, dir: File) {
        dir.mkdirs()
        downloaded(c.url, dir, ".tar.gz") { tar -> run("tar", "xzf", tar.absolutePath, "-C", dir.absolutePath) }
        dropOversized(c, dir)
        decompressInPlace(dir)
        compress(dir)
    }

    private fun tar(c: ExternalCollection, dir: File) {
        dir.mkdirs()
        downloaded(c.url, dir, ".tar") { tar -> run("tar", "xf", tar.absolutePath, "-C", dir.absolutePath) }
        decompressInPlace(dir)
        compress(dir)
    }

    private fun tarballZst(c: ExternalCollection, dir: File) {
        dir.mkdirs()
        downloaded(c.url, dir, ".tar.zst") { tar ->
            run("tar", "--zstd", "-xf", tar.absolutePath, "-C", dir.absolutePath)
        }
        compress(dir)
    }

    private fun files(c: ExternalCollection, m: FetchMethod.Files, dir: File) {
        dir.mkdirs()
        // A failed fetch leaves no directory behind: a partial one would read as a fetched collection.
        runCatching {
            for (name in m.names) {
                val text = downloaded(c.url.trimEnd('/') + "/" + name, dir, ".data") { it.readText() }
                // A server error or a redirect page comes back as HTML, which no converter should be given.
                require(!text.trimStart().startsWith("<")) { "${c.id}: $name came back as a web page, not data" }
                for ((instance, converted) in m.convert(name.substringBefore('.'), text)) {
                    File(dir, "$instance.${m.extension}").writeText(converted)
                }
            }
            compress(dir)
        }.onFailure { dir.deleteRecursively() }.getOrThrow()
    }

    // One instance at a time, so the disk never holds more than one decompressed instance beside the stored ones.
    private fun gbdFiles(c: ExternalCollection, m: FetchMethod.GbdFiles, dir: File) {
        dir.mkdirs()
        // A failed fetch leaves no directory behind: a partial one would read as a fetched collection.
        runCatching {
            for (instance in m.instances) {
                val plain = File(dir, instance.relPath)
                plain.parentFile.mkdirs()
                val packed = File(plain.path + ".xz")
                downloads.fetch(URI(c.url.trimEnd('/') + "/file/" + instance.hash), packed)
                runCmd(null, listOf("unxz", "-q", "-f", packed.absolutePath))
                val hash = plain.inputStream().use(GbdHash::of)
                require(hash == instance.hash) {
                    "${c.id}: ${instance.relPath} has content hash $hash, not the pinned ${instance.hash}"
                }
                CorpusFiles.compress(plain)
            }
            log("fetched ${m.instances.size} instance(s) into '${dir.name}'")
        }.onFailure { dir.deleteRecursively() }.getOrThrow()
    }

    private fun nameBare(dir: File, marker: String, ext: String) {
        dir.walkTopDown().filter { it.isFile && CorpusFiles.formatExtension(it).isEmpty() }.forEach { file ->
            // MPS comment lines (`*…`) may precede the marker.
            val head = CorpusFiles.open(file).bufferedReader().useLines { lines ->
                lines.map { it.trimStart() }.firstOrNull { it.isNotEmpty() && !it.startsWith("*") }
            }.orEmpty()
            if (head.startsWith(marker)) {
                val compressed = CorpusFiles.isCompressed(file)
                val plain = CorpusFiles.plainPath(file.name)
                file.renameTo(
                    File(file.parentFile, "$plain.$ext" + if (compressed) file.name.removePrefix(plain) else ""),
                )
            }
        }
    }

    private fun zip(c: ExternalCollection, dir: File) {
        dir.mkdirs()
        downloaded(c.url, dir, ".zip") { zip -> extractZip(zip, dir) }
        dropOversized(c, dir)
        decompressInPlace(dir)
        compress(dir)
    }

    private fun <T> downloaded(url: String, directory: File, suffix: String, use: (File) -> T): T {
        val temporary = Files.createTempFile(directory.parentFile.toPath(), ".archive-", suffix).toFile()
        return try {
            downloads.fetch(URI(url), temporary)
            use(temporary)
        } finally {
            temporary.delete()
        }
    }

    internal fun extractZip(archive: File, directory: File) {
        val root = directory.toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        ZipFile(archive).use { zip ->
            for (entry in zip.entries().asSequence()) {
                val target = root.resolve(entry.name).normalize()
                require(target.startsWith(root)) { "ZIP entry escapes its collection: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    val checksum = CRC32()
                    CheckedInputStream(zip.getInputStream(entry), checksum).use { input ->
                        Files.newOutputStream(target).use { input.copyTo(it) }
                    }
                    require(checksum.value == entry.crc) { "ZIP entry checksum mismatch: ${entry.name}" }
                }
            }
        }
    }

    // Runs before decompression, so giant members never inflate the cache.
    private fun dropOversized(c: ExternalCollection, dir: File) {
        val cap = c.maxFileMb?.let { it * 1024L * 1024 } ?: return
        val victims = dir.walkTopDown().filter { it.isFile && it.length() > cap }.toList()
        victims.forEach { it.delete() }
        if (victims.isNotEmpty()) log("dropped ${victims.size} file(s) over ${c.maxFileMb}MB from '${c.id}'")
    }

    // Chunked to stay under the argument-length limit.
    private fun decompressInPlace(dir: File) {
        // Some archives store read-only dirs, which forbid replacing the packed file with the plain one.
        run("chmod", "-R", "u+w", dir.absolutePath)
        for (packed in dir.walkTopDown().filter { it.isFile && it.extension == "lzma" }) unpackLzma(packed)
        for ((ext, tool) in DECOMPRESS_TOOLS) {
            val packed = dir.walkTopDown().filter { it.isFile && it.extension == ext }.map { it.absolutePath }.toList()
            if (packed.isEmpty()) continue
            log("decompressing ${packed.size} .$ext instance(s) in '${dir.name}'")
            for (chunk in packed.chunked(500)) runCmd(null, listOf(tool, "-q", "-f") + chunk)
        }
    }

    internal fun unpackLzma(packed: File) {
        val plain = File(packed.parentFile, packed.name.removeSuffix(".lzma"))
        packed.inputStream().use { raw ->
            LZMAInputStream(raw).use { input -> plain.outputStream().use { input.copyTo(it) } }
        }
        check(packed.delete()) { "cannot remove unpacked instance: $packed" }
    }

    private fun compress(dir: File) {
        val done = CorpusFiles.compressTree(dir, ::log)
        if (done.files > 0) log("compressed ${done.files} instance(s) in '${dir.name}'")
    }

    private fun run(vararg cmd: String) = runCmd(null, cmd.asList())

    private fun run(workdir: File?, vararg cmd: String) = runCmd(workdir, cmd.asList())

    private fun runCmd(workdir: File?, cmd: List<String>) {
        val pb = ProcessBuilder(cmd).inheritIO()
        if (workdir != null) pb.directory(workdir)
        val rc = pb.start().waitFor()
        require(rc == 0) { "command failed (exit $rc): ${cmd.joinToString(" ")}" }
    }

    private fun log(msg: String) = System.err.println("[corpus] $msg")
}
