package com.eignex.klause.bench.profile

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object NativeVmBoundaryEntryBinding {
    private const val MAX_REPORT_BYTES = 2 * 1024 * 1024
    private const val TIMEOUT_SECONDS = 60L
    private const val LIBRARY_SHA256 = "5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c"

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == 4 && options.keys == setOf("entries", "entries-sha256", "signatures", "signatures-sha256"))
        for (name in listOf("entries", "signatures")) {
            require(URI(requireNotNull(options[name])).scheme == "https")
            require(requireNotNull(options["$name-sha256"]).matches(Regex("[0-9a-f]{64}")))
        }
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val entries = download(requireNotNull(options["entries"]), requireNotNull(options["entries-sha256"]),
                File(out, "captured-vm-entries.json"))
            val signatures = download(requireNotNull(options["signatures"]), requireNotNull(options["signatures-sha256"]),
                File(out, "captured-vm-signatures.json"))
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val names = listOf("bind_native_vm_boundary_entries.py", "read_native_vm_entries.py",
                "read_native_vm_symbols.py", "read_native_vm_signatures.py", "native_vm_demangler.py",
                "native_vm_loaded_module.py", "read_native_vm_metadata.py")
            for (name in names) File(root, "klause-bench/tools/$name").copyTo(File(out, name))
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val command = listOf(
                "python3", File(root, "klause-bench/tools/bind_native_vm_boundary_entries.py").path,
                File(runtime, "lib/server/libjvm.so").path, LIBRARY_SHA256,
                entries.path, requireNotNull(options["entries-sha256"]),
                signatures.path, requireNotNull(options["signatures-sha256"]),
                File(out, "vm-boundary-entry-bindings.json").path,
            )
            val process = ProcessBuilder(command).directory(root)
                .redirectOutput(File(out, "boundary-bindings.stdout"))
                .redirectError(File(out, "boundary-bindings.stderr")).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            File(out, "boundary-bindings.exit").writeText("${process.exitValue()}\n")
            check(finished) { "VM source-entry association timed out; raw output retained" }
            check(process.exitValue() == 0) { "VM source-entry association failed; raw output retained" }
        }
    }

    private fun download(url: String, expected: String, destination: File): File {
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 20_000
        }
        val raw = connection.getInputStream().use { it.readNBytes(MAX_REPORT_BYTES + 1) }
        destination.writeBytes(raw)
        require(raw.size <= MAX_REPORT_BYTES) { "Captured report exceeds diagnostic bounds" }
        val actual = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        require(actual == expected) { "Captured report hash differs" }
        return destination
    }
}
