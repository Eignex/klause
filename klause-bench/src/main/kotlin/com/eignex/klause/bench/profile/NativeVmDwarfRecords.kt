package com.eignex.klause.bench.profile

import java.io.File
import java.util.concurrent.TimeUnit

internal object NativeVmDwarfRecords {
    private const val TIMEOUT_SECONDS = 600L

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            for (name in listOf(
                "read_native_vm_dwarf_records.py",
                "native_vm_dwarf_provider.py",
                "bind_native_vm_debug_image.py",
                "read_native_vm_symbols.py",
            )) {
                File(root, "klause-bench/tools/$name").copyTo(File(out, name))
            }
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val process = ProcessBuilder(
                "python3", File(root, "klause-bench/tools/read_native_vm_dwarf_records.py").path,
                File(runtime, "lib/server/libjvm.so").path, File(out, "vm-dwarf-records.json").path,
            ).directory(root).redirectOutput(File(out, "dwarf-records.stdout"))
                .redirectError(File(out, "dwarf-records.stderr")).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            File(out, "dwarf-records.exit").writeText("${process.exitValue()}\n")
            check(finished) { "VM DWARF record inspection timed out; metadata retained" }
            check(process.exitValue() == 0) { "VM DWARF record inspection failed; metadata retained" }
        }
    }
}
