package com.eignex.klause.bench.profile

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object NativeVmTaskGenerationReview {
    private const val MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024
    private const val TIMEOUT_SECONDS = 120L

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        val required = setOf("manifest", "manifest-sha256", "archive")
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
                val limit = if (name == "manifest") 4 * 1024 * 1024 else MAX_DOWNLOAD_BYTES
                val raw = connection.getInputStream().use { it.readNBytes(limit + 1) }
                val output = File(out, if (name == "manifest") "captured-manifest.json" else "captured-archive.tar.gz")
                output.writeBytes(raw)
                require(raw.size <= limit) { "Saved input exceeds review download bounds" }
                if (name == "manifest") {
                    val actual = MessageDigest.getInstance("SHA-256").digest(raw)
                        .joinToString("") { "%02x".format(Locale.ROOT, it) }
                    require(actual == expected) { "Saved manifest hash differs" }
                }
            }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val sources = listOf(
                "bind_native_vm_boundary_entries.py",
                "native_compiler_observer.py",
                "native_vm_boundary_lifecycle.py",
                "native_vm_demangler.py",
                "native_vm_entry_decoder.py",
                "native_vm_event_loss.py",
                "native_vm_live_arguments.py",
                "native_vm_loaded_module.py",
                "native_vm_task_generation.py",
                "read_native_vm_entries.py",
                "read_native_vm_metadata.py",
                "read_native_vm_signatures.py",
                "read_native_vm_symbols.py",
                "review_native_vm_boundary_lifecycle.py",
                "review_native_vm_dwarf_records.py",
                "review_native_vm_entry_abi.py",
                "review_native_vm_live_arguments.py",
                "review_native_vm_task_generation.py",
                "review_native_vm_task_lifecycle_abi.py",
            )
            for (source in sources) {
                File(root, "klause-bench/tools/$source").copyTo(File(out, source))
            }
            val tool = File(root, "klause-bench/tools/review_native_vm_task_generation.py")
            val library = File(System.getProperty("java.home"), "lib/server/libjvm.so").canonicalFile
            val command = listOf("python3", tool.path, File(out, "captured-manifest.json").path, expected,
                File(out, "captured-archive.tar.gz").path, library.path, File(out, "task-generation-review.json").path)
            val process = ProcessBuilder(command).directory(root)
                .redirectOutput(File(out, "task-review.stdout"))
                .redirectError(File(out, "task-review.stderr")).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            File(out, "task-review.exit").writeText("${process.exitValue()}\n")
            check(finished && process.exitValue() == 0) { "Saved task generation review failed; raw output retained" }
        }
    }
}
