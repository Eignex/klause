package com.eignex.klause.bench.profile

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object NativeVmTaskLifecycleAbiReview {
    private const val MAX_INPUT_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    private const val TIMEOUT_SECONDS = 60L

    @JvmStatic
    fun main(args: Array<String>) {
        val names = listOf("context", "original-context", "layout", "entries", "additional-entries")
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == 10)
        require(options.keys == names.flatMap { listOf(it, "$it-sha256") }.toSet())
        for (key in names) {
            val uri = URI(requireNotNull(options[key]))
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
            require(requireNotNull(options["$key-sha256"]).matches(Regex("[0-9a-f]{64}")))
        }
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            var totalBytes = 0L
            val inputs = names.map { key ->
                val connection = URI(requireNotNull(options[key])).toURL().openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 20_000
                }
                val raw = connection.getInputStream().use { it.readNBytes(MAX_INPUT_BYTES + 1) }
                val file = File(out, "downloaded-vm-$key.json").apply { writeBytes(raw) }
                totalBytes += raw.size
                require(raw.size <= MAX_INPUT_BYTES && totalBytes <= MAX_TOTAL_BYTES) {
                    "Lifecycle inputs exceed diagnostic bounds"
                }
                val digest = MessageDigest.getInstance("SHA-256").digest(raw)
                    .joinToString("") { "%02x".format(Locale.ROOT, it) }
                require(digest == options["$key-sha256"]) { "Lifecycle input hash differs" }
                file
            }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val tool = "review_native_vm_task_lifecycle_abi.py"
            for (name in listOf(tool, "review_native_vm_entry_abi.py", "review_native_vm_dwarf_records.py")) {
                File(root, "klause-bench/tools/$name").copyTo(File(out, name))
            }
            val command = mutableListOf("python3", File(root, "klause-bench/tools/$tool").path)
            for ((index, key) in names.withIndex()) {
                command += listOf(inputs[index].path, requireNotNull(options["$key-sha256"]))
            }
            command += File(out, "vm-task-lifecycle-abi-review.json").path
            val process = ProcessBuilder(command).directory(root)
                .redirectOutput(File(out, "task-lifecycle-abi-review.stdout"))
                .redirectError(File(out, "task-lifecycle-abi-review.stderr")).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            File(out, "task-lifecycle-abi-review.exit").writeText("${process.exitValue()}\n")
            check(finished) { "Saved lifecycle ABI review timed out; metadata retained" }
            check(process.exitValue() == 0) { "Saved lifecycle ABI review failed; metadata retained" }
        }
    }
}
