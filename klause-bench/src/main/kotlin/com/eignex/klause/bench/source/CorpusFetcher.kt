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

/**
 * Resolves a [ProblemSource] to a concrete [File] on disk.
 *
 *  - [ProblemSource.Vendored] → a path under the workspace root (tracked in git).
 *  - [ProblemSource.External] → a path inside a cached clone/download of an
 *    [ExternalCollection]; the collection is fetched on first use into [cacheRoot] and reused
 *    thereafter.
 *  - [ProblemSource.InCode] has no file and is resolved by the solver layer, not here.
 *
 * Fetched instances can be stored zstd-compressed (see [CorpusFiles]), so a resolved file may be
 * `<name>.<ext>.zst`; read it through [CorpusFiles], never directly.
 */
internal object CorpusFetcher {

    /** Compression suffix → the system decompressor that strips it in place (see [decompressInPlace]), ahead
     *  of the recompression to zstd. */
    private val DECOMPRESS_TOOLS = mapOf("xz" to "unxz", "bz2" to "bunzip2", "gz" to "gunzip")

    /** The workspace root: `-Dklause.workspace.root`, otherwise the nearest ancestor of the working
     *  directory holding `klause-mzn-lib/`. */
    fun workspaceRoot(): File {
        System.getProperty("klause.workspace.root")?.let { return File(it) }
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            if (File(cur, "klause-mzn-lib").isDirectory) return cur
            cur = cur.parentFile
        }
        error("could not locate workspace root (no klause-mzn-lib parent); set -Dklause.workspace.root")
    }

    /** Where fetched external collections are cached. Defaults outside the repo tree — under
     *  `$XDG_CACHE_HOME` (or `~/.cache`) — so multi-GB fetches survive `git clean` / a `build/`
     *  wipe and are shared across worktrees. Overridable via `-Dklause.bench.corpusCache`. */
    val cacheRoot: File
        get() = System.getProperty("klause.bench.corpusCache")?.let { File(it) }
            ?: defaultCacheRoot()

    private fun defaultCacheRoot(): File = File(xdgCacheHome(), "klause-bench/corpus")

    private val storageRoot: File
        get() = if (getBoolean("klause.bench.corpus.shared")) defaultCacheRoot() else cacheRoot

    private val downloads = CorpusDownload()
    private val mirror: CorpusMirror?
        get() {
            val address = System.getenv("LAB_CORPUS_MIRROR") ?: System.getenv("KLAUSE_BENCH_CORPUS_MIRROR")
            return address?.takeIf { it.isNotBlank() }?.let { CorpusMirror(URI(it)) }
        }

    /** `$XDG_CACHE_HOME` when set, else `~/.cache` (the XDG base-dir default). */
    private fun xdgCacheHome(): File = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".cache")

    /**
     * Whether this process plans for a lab that accepts declared input hashes in place of local bytes: the lab sets
     * `LAB_DECLARED_INPUTS` (mapped to this property by the lab adapter), publishes inputs by content hash and verifies
     * every fetched byte. Only then may a plan select from a [CorpusIndex] without materializing the collection.
     */
    val declaredInputs: Boolean get() = System.getProperty("klause.bench.declaredInputs") == "true"

    private val indexes: CorpusIndexes get() = CorpusIndexes(storageRoot)

    /**
     * [collection] by its index alone, or null when its bytes are already local (the tree is then authoritative and
     * cheap), when [declaredInputs] is off, or when no index describes its pack. A missing local index is fetched from
     * the mirror; the pack itself is never downloaded here.
     */
    fun indexed(collection: ExternalCollection): IndexedCollection? {
        if (!declaredInputs || present(collection.id)) return null
        val index = indexes.local(collection.id) ?: mirror?.let { remote ->
            storageRoot.mkdirs()
            runCatching { remote.index(collection.id, storageRoot) }.onFailure {
                System.err.println("corpus index of '${collection.id}' unavailable: ${it.javaClass.simpleName}")
            }.getOrNull()?.also(indexes::save)
        } ?: return null
        return indexes.view(index)
    }

    /** The root to discover [collection]'s instances under: its index listing when [indexed], else its fetched tree. */
    fun listing(collection: ExternalCollection): File = indexed(collection)?.root ?: ensure(collection)

    private fun present(id: String): Boolean = listOf(storageRoot, cacheRoot).any { root ->
        File(root, id).let { it.isDirectory && it.list()?.isNotEmpty() == true }
    }

    /** Resolve [source] to a file, fetching an external collection if needed. [root] locates a collection's tree; an
     *  index listing ([IndexedCollection.root]) resolves the same relative path without the collection's bytes. */
    fun resolve(source: ProblemSource, root: (ExternalCollection) -> File = ::ensure): File = when (source) {
        is ProblemSource.Prepared -> source.file.also { require(it.isFile) }

        is ProblemSource.Vendored -> File(workspaceRoot(), source.workspaceRelPath).also {
            require(it.isFile) { "vendored file missing: ${source.workspaceRelPath}" }
        }

        is ProblemSource.External -> stored(File(root(source.collection), source.relPath)).also {
            require(it.isFile) { "instance '${source.relPath}' not found in collection '${source.collection.id}'" }
        }

        is ProblemSource.ExternalIndexed -> {
            val files = root(source.collection).walkTopDown()
                .filter { it.isFile && CorpusFiles.formatExtension(it) == source.ext }
                .sortedBy { CorpusFiles.plainPath(it.name) }
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

    /** The MiniZinc include directories [source]'s collection ships, which `minizinc -I` needs beside the model. */
    fun includeDirs(source: ProblemSource): List<File> = if (source is ProblemSource.Prepared) {
        source.includes
    } else if (source is ProblemSource.External) {
        val root = ensure(source.collection)
        source.collection.includeDirs.map { File(root, it) }.filter { it.isDirectory }
    } else {
        emptyList()
    }

    private fun mirrorWrite(): Boolean =
        (System.getenv("LAB_CORPUS_MIRROR_WRITE") ?: System.getenv("KLAUSE_BENCH_CORPUS_MIRROR_WRITE")) == "true"

    /** [file] when present, otherwise its plain or compressed counterpart. Catalog paths may name either form. */
    private fun stored(file: File): File {
        if (file.isFile) return file
        val counterpart = if (CorpusFiles.isCompressed(file)) {
            File(CorpusFiles.plainPath(file.path))
        } else {
            File(file.path + CorpusFiles.SUFFIX)
        }
        return if (counterpart.isFile) counterpart else file
    }

    /** Collections resolved in this JVM, which the cache cap never evicts. */
    private val usedThisRun: MutableSet<String> = HashSet()

    /** The size-capped view of [cacheRoot] (see [CorpusCache]), pinning what this run has resolved. */
    fun cache(): CorpusCache = CorpusCache(storageRoot, CorpusCache.configuredCapBytes(), usedThisRun, log = ::log)

    /** The root directory of [collection] in the cache, fetching it if not already present. A fetch first
     *  evicts least recently used collections to fit the cache cap, and again once the new one has landed. */
    fun ensure(collection: ExternalCollection): File {
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
        // MIPLIB 2010 ships `*.mps.gz` inside its tarball, some of them too large to be worth keeping.
        dropOversized(c, dir)
        decompressInPlace(dir)
        compress(dir)
    }

    private fun tar(c: ExternalCollection, dir: File) {
        dir.mkdirs()
        downloaded(c.url, dir, ".tar") { tar -> run("tar", "xf", tar.absolutePath, "-C", dir.absolutePath) }
        // PB competition instances ship individually `*.opb.xz`/`*.wbo.xz`; recompress them to zstd.
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

    /** One instance at a time, so the disk never holds more than one decompressed instance beside the stored ones. */
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

    /** Give each extension-less file under [dir] whose first line that is not a comment starts with [marker] the
     *  extension [ext]. */
    private fun nameBare(dir: File, marker: String, ext: String) {
        dir.walkTopDown().filter { it.isFile && CorpusFiles.formatExtension(it).isEmpty() }.forEach { file ->
            // MPS comment lines (`*…`) may come first: MIPLIB 3 heads each file with a commented summary.
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
        // Competition archives ship individually compressed instances, which need unpacking before zstd.
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

    /** Drop extracted files over [ExternalCollection.maxFileMb] (measured on the still-compressed
     *  size) before decompression, so a huge archive's giant members never inflate the cache. */
    private fun dropOversized(c: ExternalCollection, dir: File) {
        val cap = c.maxFileMb?.let { it * 1024L * 1024 } ?: return
        val victims = dir.walkTopDown().filter { it.isFile && it.length() > cap }.toList()
        victims.forEach { it.delete() }
        if (victims.isNotEmpty()) log("dropped ${victims.size} file(s) over ${c.maxFileMb}MB from '${c.id}'")
    }

    /** Decompress every individually-compressed instance under [dir] in place, stripping the
     *  compression suffix (`foo.cnf.xz` → `foo.cnf`) so [compress] can store it as zstd. Covers the
     *  suffixes competition corpora use; a no-op for anything already plain. Chunked to stay under the
     *  argument-length limit. */
    private fun decompressInPlace(dir: File) {
        // Some archives (e.g. the 2007 PB tarball) store read-only dirs/files; decompressing in place
        // must write the plain file and unlink the packed one, which a read-only parent dir forbids.
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

    /** Store every instance under a freshly extracted [dir] zstd-compressed. */
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
