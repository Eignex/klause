package com.eignex.klause.bench.profile

import java.io.File
import java.util.concurrent.TimeUnit

internal object NativeVmEntryVerification {
    private const val TIMEOUT_SECONDS = 90L
    private const val LIBRARY_SHA256 = "5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val names = listOf("read_native_vm_entries.py", "read_native_vm_symbols.py", "native_vm_entry_decoder.py")
            for (name in names) File(root, "klause-bench/tools/$name").copyTo(File(out, name))
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val command = listOf(
                "python3", File(root, "klause-bench/tools/read_native_vm_entries.py").path,
                File(runtime, "lib/server/libjvm.so").path, LIBRARY_SHA256, File(out, "vm-entries.json").path,
            )
            val process = ProcessBuilder(command).directory(root)
                .redirectOutput(File(out, "entries.stdout"))
                .redirectError(File(out, "entries.stderr")).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            File(out, "entries.exit").writeText("${process.exitValue()}\n")
            check(finished) { "VM entry inspection timed out; raw output retained" }
            check(process.exitValue() == 0) { "VM entry inspection failed; raw output retained" }
        }
    }
}
