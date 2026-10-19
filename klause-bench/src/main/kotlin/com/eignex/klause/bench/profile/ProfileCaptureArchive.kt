package com.eignex.klause.bench.profile

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object ProfileCaptureArchive {
    private const val TRANSFER_MINUTES = 10L

    fun run(out: File? = null, action: () -> Unit) = capture(out, null, action)

    fun run(out: File? = null, budget: ProfileRunBudget, action: () -> Unit) = capture(out, budget, action)

    private fun capture(out: File?, budget: ProfileRunBudget?, action: () -> Unit) {
        val result = runCatching(action)
        val archive = runCatching { write(out ?: File(requireNotNull(System.getenv("OUT"))), budget) }
        result.exceptionOrNull()?.let { original -> archive.exceptionOrNull()?.let(original::addSuppressed) }
        result.getOrThrow()
        archive.getOrThrow()
    }

    private fun write(out: File, budget: ProfileRunBudget?) {
        val stage = Files.createTempDirectory(out.parentFile.toPath(), "klause-capture-").toFile()
        val verification = Files.createTempDirectory(out.parentFile.toPath(), "klause-capture-verify-").toFile()
        val archive = File(out, "capture.tar.gz")
        check(!archive.exists())
        val children = out.listFiles().orEmpty().filter { it.name != "profile.jfr" }
        val temporary = File(stage, "capture.tar.gz")
        val packed = runCatching {
            budget?.remainingMillis()
            val files = manifest(out, children, budget)
            val inputs = if (children.isEmpty()) {
                listOf("--files-from=/dev/null")
            } else {
                listOf("--") + children.map { it.name }
            }
            command(out, "capture-pack", listOf("tar", "-C", out.path, "-czf", temporary.path) + inputs, budget)
            command(out, "capture-verify", listOf("tar", "-C", verification.path, "-xzf", temporary.path), budget)
            check(manifest(verification, budget = budget) == files) { "Compressed capture changed a file" }
            Files.move(temporary.toPath(), archive.toPath())
            File(out, "capture-manifest.json").writeText(
                Reports.json.encodeToString(
                CaptureManifest(sha256(archive, budget), archive.length(), files),
            )
            )
            for (child in children) check(child.deleteRecursively()) { "Cannot remove verified capture staging" }
        }
        stage.deleteRecursively()
        verification.deleteRecursively()
        packed.getOrThrow()
    }

    private fun manifest(
        directory: File,
        children: List<File> = listOf(directory),
        budget: ProfileRunBudget? = null,
    ): Map<String, CaptureFile> = children.asSequence().flatMap { child ->
        child.walkTopDown().onEnter {
            check(!Files.isSymbolicLink(it.toPath())) { "Capture contains a symbolic link" }
            true
        }
    }.filter {
        check(!Files.isSymbolicLink(it.toPath())) { "Capture contains a symbolic link" }
        it.isFile
    }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
        .associate {
            it.relativeTo(directory).invariantSeparatorsPath to CaptureFile(it.length(), sha256(it, budget))
        }

    private fun sha256(file: File, budget: ProfileRunBudget?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { input ->
            if (budget == null) {
                input.transferTo(OutputStream.nullOutputStream())
            } else {
                val buffer = ByteArray(64 * 1024)
                while (input.read(buffer) >= 0) budget.remainingMillis()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun command(out: File, name: String, arguments: List<String>, budget: ProfileRunBudget?) {
        val waitMillis = budget?.remainingMillis() ?: TimeUnit.MINUTES.toMillis(TRANSFER_MINUTES)
        val process = ProcessBuilder(arguments).redirectOutput(File(out, "$name.stdout"))
            .redirectError(File(out, "$name.stderr")).start()
        try {
            check(process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) { "Capture command timed out: $name" }
            check(process.exitValue() == 0) { "Capture command failed: $name" }
        } finally {
            if (process.isAlive) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly().waitFor()
            }
        }
    }
}

@Serializable
internal data class CaptureFile(val bytes: Long, val sha256: String)

@Serializable
internal data class CaptureManifest(
    val archiveSha256: String,
    val archiveBytes: Long,
    val files: Map<String, CaptureFile>,
    val schema: Int = 1,
    val scope: String = "Complete untimed capture after the original study; no raw file or statistic removed. " +
        "The live parent profile.jfr stays outside the archive and is finalized by the lab wrapper.",
)
