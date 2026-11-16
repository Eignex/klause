package com.eignex.klause.bench.metric

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.HexFormat

internal object SourceDecoder {
    fun capture(checkActive: () -> Unit, path: String = System.getenv("PATH").orEmpty()): Map<String, String> {
        checkActive()
        val executable = path.split(File.pathSeparator).asSequence().map { File(it, "zstd") }
            .firstOrNull { it.isFile && it.canExecute() }?.canonicalFile
            ?: throw IOException("Compressed CLI delivery requires zstd on PATH")
        val digest = MessageDigest.getInstance("SHA-256")
        executable.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                checkActive()
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return mapOf("sourceDecoderExecutable" to executable.path,
            "sourceDecoderSha256" to HexFormat.of().formatHex(digest.digest()))
    }
}
