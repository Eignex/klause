package com.eignex.klause.bench.metric

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.zip.ZipInputStream

internal object SmtSourceTools {
    private const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
    private const val MAX_BINARY_BYTES = 128 * 1024 * 1024

    private data class Artifact(val url: String, val digest: String, val entry: String? = null)

    fun resolve(
        scope: String,
        directory: File,
        refutationSolver: SmtSourceValidation.RefutationSolver = SmtSourceValidation.RefutationSolver.Z3,
    ): SmtSourceValidation.Tool {
        val name = if (scope == SmtSourceValidation.GROUND) "DOLMEN" else refutationSolver.name
        val executable = System.getenv("KLAUSE_$name")
        val digest = System.getenv("KLAUSE_${name}_SHA256")
        require((executable == null) == (digest == null)) { "KLAUSE_$name and its SHA256 must be configured together" }
        return if (executable == null) provision(scope, directory, refutationSolver) else {
            SmtSourceValidation.Tool(File(executable), requireNotNull(digest), refutationSolver = refutationSolver)
        }
    }

    fun provision(
        scope: String,
        directory: File,
        refutationSolver: SmtSourceValidation.RefutationSolver = SmtSourceValidation.RefutationSolver.Z3,
    ): SmtSourceValidation.Tool {
        require(scope in setOf(SmtSourceValidation.GROUND, SmtSourceValidation.REFUTATION))
        val mac = System.getProperty("os.name") == "Mac OS X"
        val linux = System.getProperty("os.name") == "Linux"
        val arm = System.getProperty("os.arch") in setOf("aarch64", "arm64")
        val x64 = System.getProperty("os.arch") in setOf("amd64", "x86_64")
        val dolmen = scope == SmtSourceValidation.GROUND
        val artifact = if (dolmen) {
            val prefix = "https://github.com/Gbury/dolmen/releases/download/v0.10/"
            when {
                linux && x64 -> Artifact(prefix + "dolmen-linux-amd64",
                    "bab3e9a1007d5bf6fe5323195e98f95c812fbb108b95a9e02a693ebc4014b675")
                // The release's macOS filename labels an ARM64 Mach-O executable as amd64.
                mac && arm -> Artifact(prefix + "dolmen-macos-amd64",
                    "2141ed2e479f4a0fd97723f34f42cae66156a8c5a20d29da919b12b373da47a1")
                else -> error("no pinned Dolmen release for this platform; configure an external executable")
            }
        } else if (refutationSolver == SmtSourceValidation.RefutationSolver.CVC5) {
            val (name, digest) = when {
                linux && x64 -> "cvc5-Linux-x86_64-static" to
                    "7eb18f8c814c36a46c5a7d003983e9cfbc4b0a9828d6dd596239291bd4cc61d8"
                linux && arm -> "cvc5-Linux-arm64-static" to
                    "765ebee59d9e5efccb60a445fa66fbddfb07a8de9fe7a5b6d128e5c2aafec974"
                mac && arm -> "cvc5-macOS-arm64-static" to
                    "24397f7d022e755876ca836193d4c5f9c1d4326f0f6c01f7d7fc1ddd6bc5096c"
                mac && x64 -> "cvc5-macOS-x86_64-static" to
                    "f2bbbfe089c77c4a676dd05f262eee86b9718c32374c424138c416024ff42477"
                else -> error("no pinned cvc5 release for this platform; configure an external executable")
            }
            Artifact("https://github.com/cvc5/cvc5/releases/download/cvc5-1.4.2/$name.zip", digest, "$name/bin/cvc5")
        } else {
            val (name, digest) = when {
                linux && x64 -> "z3-4.15.3-x64-glibc-2.39" to
                    "94549c5e31a75b9c543fe6eea8f32927054765c2e92e696d2d6dde0eedf348a1"
                mac && arm -> "z3-4.15.3-arm64-osx-13.7.6" to
                    "941659417b5464a361c49089658509f3118a0c3e8d4f8a1dc999f8b5cd1f3c71"
                mac && x64 -> "z3-4.15.3-x64-osx-13.7.6" to
                    "82df675b8b7c4af2e7d8ef94056fcd6fb626198e2855d976134c878f243c96a0"
                linux && arm -> "z3-4.15.3-arm64-glibc-2.34" to
                    "78b383374905a20af7f38cb3e8e9e8e38c5cb3d23a8c2fbf8f54ff4b41a9c605"
                else -> error("no pinned Z3 release for this platform; configure an external executable")
            }
            Artifact("https://github.com/Z3Prover/z3/releases/download/z3-4.15.3/$name.zip", digest, "$name/bin/z3")
        }
        val archive = download(artifact.url)
        check(sha256(archive) == artifact.digest) { "external checker release hash differs" }
        val bytes = if (artifact.entry == null) archive else ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = requireNotNull(zip.nextEntry) { "external checker executable absent from release" }
                if (entry.name == artifact.entry) break
            }
            zip.readNBytes(MAX_BINARY_BYTES + 1)
        }
        require(bytes.size in 1..MAX_BINARY_BYTES) { "external checker executable exceeds limit" }
        check(directory.isDirectory || directory.mkdirs())
        val binary = File(directory, if (dolmen) "dolmen" else refutationSolver.name.lowercase()).absoluteFile
        check(!binary.exists()) { "external checker staging path exists" }
        val tool = SmtSourceValidation.Tool(binary, sha256(bytes), artifact.url, artifact.digest, owned = true,
            refutationSolver = refutationSolver)
        var complete = false
        try {
            binary.writeBytes(bytes)
            check(binary.setExecutable(true, true)) { "external checker cannot be made executable" }
            complete = true
            return tool
        } finally {
            if (!complete) binary.delete()
        }
    }

    private fun download(url: String): ByteArray {
        val started = System.nanoTime()
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 10_000
            readTimeout = 5_000
        }
        return connection.getInputStream().use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    check(!Thread.currentThread().isInterrupted && System.nanoTime() - started < 30_000_000_000) {
                        "external checker download interrupted or timed out"
                    }
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_ARCHIVE_BYTES) { "external checker release exceeds limit" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }
}
