package com.eignex.klause.bench.profile

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object NativeVmLiveArgumentReview {
    private const val MAX_DOWNLOAD_BYTES = 128 * 1024 * 1024
    private const val TIMEOUT_SECONDS = 120L

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        val review = options["review"]
        require(review == null || review in setOf("event-loss", "log-identities", "return-sizes", "task-log"))
        val required = setOf("manifest", "manifest-sha256", "archive") +
            if (review != null) setOf("review") else emptySet()
        require(args.size == required.size && options.keys == required)
        val expected = requireNotNull(options["manifest-sha256"])
        require(expected.matches(Regex("[0-9a-f]{64}")))
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            for (name in listOf("manifest", "archive")) {
                val uri = URI(requireNotNull(options[name]))
                require(uri.scheme == "https" && uri.rawUserInfo == null)
                val connection = uri.toURL().openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 20_000
                }
                val raw = connection.getInputStream().use { it.readNBytes(MAX_DOWNLOAD_BYTES + 1) }
                val output = File(out, if (name == "manifest") "captured-manifest.json" else "captured-archive.tar.gz")
                output.writeBytes(raw)
                require(raw.size <= MAX_DOWNLOAD_BYTES) { "Saved input exceeds review download bounds" }
                if (name == "manifest") {
                    val actual = MessageDigest.getInstance("SHA-256").digest(raw)
                        .joinToString("") { "%02x".format(Locale.ROOT, it) }
                    require(actual == expected) { "Saved manifest hash differs" }
                }
            }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val name = when (review) {
                "event-loss" -> "review_native_vm_event_loss.py"
                "log-identities" -> "review_native_vm_log_identities.py"
                "return-sizes" -> "review_native_vm_return_sizes.py"
                "task-log" -> "review_jvm_task_log.py"
                else -> "review_native_vm_live_arguments.py"
            }
            val tool = File(root, "klause-bench/tools/$name")
            val sources = listOf(name, "native_vm_event_loss.py", "review_native_vm_live_arguments.py") +
                if (review in setOf("return-sizes", "task-log")) {
                    listOf("review_native_vm_log_identities.py")
                } else emptyList()
            for (source in sources.distinct()) {
                File(root, "klause-bench/tools/$source").copyTo(File(out, source))
            }
            val command = listOf("python3", tool.path, File(out, "captured-manifest.json").path, expected,
                File(out, "captured-archive.tar.gz").path, File(out, "live-argument-review.json").path)
            val process = ProcessBuilder(command).directory(root)
                .redirectOutput(File(out, "live-review.stdout"))
                .redirectError(File(out, "live-review.stderr")).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            File(out, "live-review.exit").writeText("${process.exitValue()}\n")
            check(finished && process.exitValue() == 0) { "Saved live argument review failed; raw output retained" }
        }
    }
}
