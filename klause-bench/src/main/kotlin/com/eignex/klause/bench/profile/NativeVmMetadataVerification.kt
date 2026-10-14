package com.eignex.klause.bench.profile

import java.io.File
import java.util.concurrent.TimeUnit

internal object NativeVmMetadataVerification {
    private const val TIMEOUT_SECONDS = 60L
    private const val LIBRARY_SHA256 = "5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val tool = File(root, "klause-bench/tools/read_native_vm_metadata.py")
            tool.copyTo(File(out, tool.name))
            val loader = File(root, "klause-bench/tools/native_vm_loaded_module.py")
            loader.copyTo(File(out, loader.name))
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val command = listOf(
                "python3", tool.path, File(runtime, "lib/server/libjvm.so").path,
                LIBRARY_SHA256, File(out, "vm-metadata.json").path,
            )
            val process = ProcessBuilder(command).directory(root)
                .redirectOutput(File(out, "metadata.stdout"))
                .redirectError(File(out, "metadata.stderr")).start()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor()
                error("VM metadata inspection timed out; raw output retained")
            }
            File(out, "metadata.exit").writeText("${process.exitValue()}\n")
            check(process.exitValue() == 0) { "VM metadata inspection failed; raw output retained" }
        }
    }
}
