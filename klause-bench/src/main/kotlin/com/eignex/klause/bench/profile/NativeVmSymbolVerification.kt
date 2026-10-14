package com.eignex.klause.bench.profile

import java.io.File
import java.util.concurrent.TimeUnit

internal object NativeVmSymbolVerification {
    private const val TIMEOUT_SECONDS = 60L
    private const val LIBRARY_SHA256 = "5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 16 && args.distinct().size == args.size)
        require(args.all { it.length in 1..128 && it.matches(Regex("[A-Za-z0-9_]+")) })
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val tool = File(root, "klause-bench/tools/read_native_vm_symbols.py")
            tool.copyTo(File(out, tool.name))
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val command = listOf(
                "python3", tool.path, File(runtime, "lib/server/libjvm.so").path,
                LIBRARY_SHA256, File(out, "vm-symbols.json").path,
            ) + args
            val process = ProcessBuilder(command).directory(root)
                .redirectOutput(File(out, "symbols.stdout"))
                .redirectError(File(out, "symbols.stderr")).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            File(out, "symbols.exit").writeText("${process.exitValue()}\n")
            check(finished) { "VM symbol inspection timed out; raw output retained" }
            check(process.exitValue() == 0) { "VM symbol inspection failed; raw output retained" }
        }
    }
}
