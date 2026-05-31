package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.ExternalCollection
import com.eignex.klause.bench.catalog.FetchMethod
import com.eignex.klause.bench.catalog.ProblemSource
import java.io.File
import java.net.URI

/**
 * Resolves a [ProblemSource] to a concrete [File] on disk.
 *
 *  - [ProblemSource.Vendored] → a path under the workspace root (tracked in git).
 *  - [ProblemSource.External] → a path inside a cached clone/download of an
 *    [ExternalCollection]; the collection is fetched **on first use** (automatically and
 *    transparently, with a clear log line) into [cacheRoot] and reused thereafter.
 *  - [ProblemSource.InCode] has no file and is resolved by the solver layer, not here.
 *
 * This replaces the old build-time `downloadMzn*`/`downloadSatlib` Gradle tasks and the
 * ad-hoc directory walking in the former `MznParityCorpus` / `SatlibLoader`.
 */
object CorpusFetcher {

    /** Resolve the workspace root. Honors `-Dklause.workspace.root`; otherwise walks up from
     *  the working directory looking for the `klause-mzn-lib/` marker. */
    fun workspaceRoot(): File {
        System.getProperty("klause.workspace.root")?.let { return File(it) }
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            if (File(cur, "klause-mzn-lib").isDirectory) return cur
            cur = cur.parentFile
        }
        error("could not locate workspace root (no klause-mzn-lib parent); set -Dklause.workspace.root")
    }

    /** Where fetched external collections are cached. */
    val cacheRoot: File
        get() = File(System.getProperty("klause.bench.corpusCache") ?: "klause-bench/build/corpus-cache")

    /** Resolve [source] to a file, fetching an external collection if needed. */
    fun resolve(source: ProblemSource): File = when (source) {
        is ProblemSource.Vendored -> File(workspaceRoot(), source.workspaceRelPath).also {
            require(it.isFile) { "vendored file missing: ${source.workspaceRelPath}" }
        }
        is ProblemSource.External -> File(ensure(source.collection), source.relPath).also {
            require(it.isFile) { "instance '${source.relPath}' not found in collection '${source.collection.id}'" }
        }
        is ProblemSource.InCode -> error("InCode sources have no file")
    }

    /** The root directory of [collection] in the cache, fetching it if not already present. */
    fun ensure(collection: ExternalCollection): File {
        val dir = File(cacheRoot, collection.id)
        if (dir.isDirectory && (dir.list()?.isNotEmpty() == true)) return dir
        cacheRoot.mkdirs()
        log("fetching '${collection.id}' (${collection.license}) — ${collection.reason}")
        when (val m = collection.fetch) {
            is FetchMethod.GitClone -> gitClone(collection, m, dir)
            FetchMethod.Tarball -> tarball(collection, dir)
        }
        return dir
    }

    private fun gitClone(c: ExternalCollection, m: FetchMethod.GitClone, dir: File) {
        if (m.sparsePath != null) {
            run("git", "clone", "--filter=blob:none", "--no-checkout", "--depth", m.depth.toString(), c.url, dir.absolutePath)
            run(dir, "git", "sparse-checkout", "set", m.sparsePath)
            run(dir, "git", "checkout")
        } else {
            run("git", "clone", "--depth", m.depth.toString(), c.url, dir.absolutePath)
        }
    }

    private fun tarball(c: ExternalCollection, dir: File) {
        dir.mkdirs()
        val tar = File(cacheRoot, "${c.id}.tar.gz")
        URI(c.url).toURL().openStream().use { input -> tar.outputStream().use { input.copyTo(it) } }
        run("tar", "xzf", tar.absolutePath, "-C", dir.absolutePath)
        tar.delete()
    }

    private fun run(vararg cmd: String) = run(null, *cmd)

    private fun run(workdir: File?, vararg cmd: String) {
        val pb = ProcessBuilder(*cmd).inheritIO()
        if (workdir != null) pb.directory(workdir)
        val rc = pb.start().waitFor()
        require(rc == 0) { "command failed (exit $rc): ${cmd.joinToString(" ")}" }
    }

    private fun log(msg: String) = println("[corpus] $msg")
}
