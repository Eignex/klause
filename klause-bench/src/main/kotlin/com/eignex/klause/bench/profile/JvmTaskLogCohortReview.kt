package com.eignex.klause.bench.profile

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object JvmTaskLogCohortReview {
    private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
    private const val TIMEOUT_SECONDS = 2400L

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        val required = setOf("manifest", "manifest-sha256", "archive")
        require(args.size == required.size && options.keys == required)
        val expected = options.getValue("manifest-sha256")
        require(expected.matches(Regex("[0-9a-f]{64}")))
        for (name in listOf("manifest", "archive")) validatedUri(options.getValue(name))
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            File(out, "input-origins.json").writeText(buildJsonObject {
                for ((name, value) in options) put(name, value)
            }.toString() + "\n")
            val temporary = Files.createTempDirectory("klause-task-log-cohort-").toFile()
            try {
                val manifest = File(out, "captured-manifest.json")
                require(download(options.getValue("manifest"), manifest, MAX_MANIFEST_BYTES) == expected)
                val archive = File(temporary, "original-archive.tar.gz")
                val archiveHash = download(options.getValue("archive"), archive, MAX_ARCHIVE_BYTES)
                File(out, "downloaded-archive.sha256").writeText("$archiveHash\n")
                review(out, manifest, expected, archive)
            } finally {
                check(temporary.deleteRecursively()) { "Cannot remove owned saved-log transport staging" }
            }
        }
    }

    private fun download(address: String, output: File, limit: Long): String {
        val connection = validatedUri(address).toURL().openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 20_000
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        connection.getInputStream().use { source ->
            output.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= limit) { "Saved cohort download exceeds bounds" }
                    digest.update(buffer, 0, count)
                    target.write(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun validatedUri(address: String): URI {
        require(address.length <= 4096)
        val uri = URI(address)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        return uri
    }

    private fun review(out: File, manifest: File, expected: String, archive: File) {
        val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
        val names = listOf("review_jvm_task_log_cohort.py", "review_jvm_task_log.py", "stream_jvm_task_log.py",
            "review_native_vm_log_identities.py", "review_native_vm_live_arguments.py", "native_vm_event_loss.py")
        for (name in names) File(root, "klause-bench/tools/$name").copyTo(File(out, name))
        val tool = File(root, "klause-bench/tools/review_jvm_task_log_cohort.py")
        val command = listOf("python3", tool.path, manifest.path, expected, archive.path, out.path)
        val process = ProcessBuilder(command).directory(root)
            .redirectOutput(File(out, "cohort-review.stdout"))
            .redirectError(File(out, "cohort-review.stderr")).start()
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly().waitFor()
        File(out, "cohort-review.exit").writeText("${process.exitValue()}\n")
        check(finished && process.exitValue() == 0) { "Saved cohort review failed; raw outputs retained" }
    }
}
