package com.eignex.klause.bench.profile

import java.io.File
import java.util.concurrent.TimeUnit

internal object NativeVmDebugImageBinding {
    private const val TIMEOUT_SECONDS = 300L

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            for (name in listOf("bind_native_vm_debug_image.py", "read_native_vm_symbols.py")) {
                File(root, "klause-bench/tools/$name").copyTo(File(out, name))
            }
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val command = listOf(
                "python3", File(root, "klause-bench/tools/bind_native_vm_debug_image.py").path,
                File(runtime, "lib/server/libjvm.so").path, File(out, "vm-debug-image-binding.json").path,
            )
            val process = ProcessBuilder(command).directory(root)
                .redirectOutput(File(out, "debug-image-binding.stdout"))
                .redirectError(File(out, "debug-image-binding.stderr")).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            File(out, "debug-image-binding.exit").writeText("${process.exitValue()}\n")
            check(finished) { "VM external debug binding timed out; metadata retained" }
            check(process.exitValue() == 0) { "VM external debug binding failed; metadata retained" }
        }
    }
}
