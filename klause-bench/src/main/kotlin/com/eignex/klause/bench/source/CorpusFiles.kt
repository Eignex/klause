package com.eignex.klause.bench.source

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Reads and writes corpus instance files, which the cache stores zstd-compressed as `<name>.<ext>.zst`.
 * zstd because decompression lands inside the measured solve time and it decodes at memory speed.
 * Plain files read the same way, so vendored instances and a cache that predates compression keep
 * working. Every reader of an instance file goes through [open], [readText] or [withPlainFile].
 */
internal object CorpusFiles {

    /** The compression suffix of a stored instance. */
    const val SUFFIX = ".zst"

    /** Instance extensions the cache stores compressed. MiniZinc sources (`.mzn`, `.dzn`, `.json`) are
     *  absent on purpose: `minizinc` reads them as real files and resolves includes beside them. */
    val COMPRESSIBLE = setOf("cnf", "wcnf", "opb", "wbo", "xml", "smt2", "mps")

    /** zstd decodes at the same speed at every level, so the level only trades one-off compression time
     *  for size; 12 is well past the default ratio while staying far faster than the 19+ levels. */
    private const val LEVEL = 12

    private const val PARTIAL_SUFFIX = "$SUFFIX.part"

    private const val PROGRESS_EVERY = 1000

    private const val DIGEST_BUFFER_BYTES = 1 shl 16

    /** Where [withPlainFile] stages decompressed copies: under the cache root (so inside `$HOME`, which a
     *  colima docker VM mounts) and outside every collection directory, so discovery never walks into it. */
    private val stagingRoot: File get() = File(CorpusFetcher.cacheRoot, ".plain")

    fun isCompressed(file: File): Boolean = file.name.endsWith(SUFFIX)

    /** [path] without the compression suffix: the name a stored instance has as plain text. */
    fun plainPath(path: String): String = path.removeSuffix(SUFFIX)

    /** The format extension of [file], looking past the compression suffix (`foo.mps.zst` → `mps`). */
    fun formatExtension(file: File): String = plainPath(file.name).substringAfterLast('.', "")

    /** The plain bytes of [file], decompressing a `.zst` through the system `zstd`. */
    fun open(file: File): InputStream = if (isCompressed(file)) decompress(file) else file.inputStream()

    fun readText(file: File): String = open(file).bufferedReader().use { it.readText() }

    /** Feed the plain bytes of [file] into [digest], so a compressed and a plain copy hash alike. */
    fun update(digest: MessageDigest, file: File) = open(file).use { it.feed(digest) }

    /** Run [block] on a plain-text copy of [file], for a consumer that needs a real path (a docker mount,
     *  a solver that cannot read stdin). A plain [file] is passed through; a compressed one is staged
     *  under its plain name and deleted afterwards. */
    fun <T> withPlainFile(file: File, block: (File) -> T): T {
        if (!isCompressed(file)) return block(file)
        stagingRoot.mkdirs()
        val dir = Files.createTempDirectory(stagingRoot.toPath(), "plain-").toFile()
        try {
            val plain = File(dir, plainPath(file.name))
            open(file).use { input -> plain.outputStream().use { input.copyTo(it) } }
            return block(plain)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Replace the plain instance [plain] with `<plain>.zst`, returning the compressed file. The archive is
     *  written to a partial file and renamed only once its decompressed bytes hash equal to the original,
     *  and the original is deleted only after that, so an interruption at any point loses nothing. */
    fun compress(plain: File): File {
        val packed = File(plain.path + SUFFIX)
        val partial = File(plain.path + PARTIAL_SUFFIX)
        exec(listOf("zstd", "-q", "-f", "-$LEVEL", plain.absolutePath, "-o", partial.absolutePath))
        val expected = sha256 { update(it, plain) }
        val actual = sha256 { digest -> decompress(partial).use { it.feed(digest) } }
        if (!expected.contentEquals(actual)) {
            partial.delete()
            error("zstd round trip of $plain does not reproduce its bytes")
        }
        Files.move(partial.toPath(), packed.toPath(), StandardCopyOption.REPLACE_EXISTING)
        plain.delete()
        return packed
    }

    /** Totals of a [compressTree] pass. */
    data class Compressed(val files: Int, val plainBytes: Long, val packedBytes: Long)

    /** Compress every plain [COMPRESSIBLE] instance under [root] in place. Idempotent: compressed files are
     *  skipped, and a partial archive left by an interrupted pass is discarded and redone. Git checkouts
     *  are skipped whole, since they hold MiniZinc corpora that `minizinc` and git read as real files. */
    fun compressTree(root: File, log: (String) -> Unit = {}): Compressed {
        val staging = stagingRoot.absoluteFile
        val tree = root.walkTopDown()
            .onEnter { it.name != ".git" && !File(it, ".git").exists() && it.absoluteFile != staging }
            .filter { it.isFile }
            .toList()
        tree.filter { it.name.endsWith(PARTIAL_SUFFIX) }.forEach { it.delete() }
        val pending = tree.filter { !isCompressed(it) && it.extension in COMPRESSIBLE }
        var plainBytes = 0L
        var packedBytes = 0L
        pending.forEachIndexed { i, file ->
            plainBytes += file.length()
            packedBytes += compress(file).length()
            if ((i + 1) % PROGRESS_EVERY == 0) log("compressed ${i + 1}/${pending.size} instance(s) under $root")
        }
        return Compressed(pending.size, plainBytes, packedBytes)
    }

    private fun sha256(feed: (MessageDigest) -> Unit): ByteArray =
        MessageDigest.getInstance("SHA-256").also(feed).digest()

    private fun InputStream.feed(digest: MessageDigest) {
        val buffer = ByteArray(DIGEST_BUFFER_BYTES)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }

    private fun decompress(file: File): InputStream {
        val proc = try {
            ProcessBuilder("zstd", "-dcq", file.absolutePath)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        } catch (e: IOException) {
            throw IllegalStateException("cannot run zstd to read $file; is zstd installed?", e)
        }
        return ProcessInputStream(proc, file)
    }

    private fun exec(cmd: List<String>) {
        val rc = try {
            ProcessBuilder(cmd).inheritIO().start().waitFor()
        } catch (e: IOException) {
            throw IllegalStateException("cannot run ${cmd.first()}; is it installed?", e)
        }
        check(rc == 0) { "command failed (exit $rc): ${cmd.joinToString(" ")}" }
    }

    /** A decompressor's stdout that checks the exit code once fully read, so a corrupt archive fails the
     *  read instead of yielding truncated text. Closing early stops the decompressor. */
    private class ProcessInputStream(private val proc: Process, private val file: File) :
        FilterInputStream(proc.inputStream) {
        private var drained = false

        override fun read(): Int = super.read().also { if (it < 0) drained = true }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it < 0) drained = true }

        override fun close() {
            super.close()
            if (!drained) {
                proc.destroy()
                proc.waitFor()
                return
            }
            val rc = proc.waitFor()
            check(rc == 0) { "zstd failed decompressing $file (exit $rc)" }
        }
    }
}
