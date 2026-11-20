package com.eignex.gradle

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile

/** Exact source and runtime identities for an opt-in benchmark harness build. */
class FrozenHarnessLock private constructor(private val values: Map<String, String>) {
    /** Source commit required in the containing checkout's reachable history. */
    val revision: String get() = values.getValue("revision")

    private val classpath = values.getValue("classpath").split(',')

    /** Verify the complete ordered JAR set and publish its local identity receipt. */
    fun verify(distribution: File, receipt: File) {
        check(Files.isDirectory(distribution.toPath(), NOFOLLOW_LINKS))
        Files.walk(distribution.toPath()).use { paths -> paths.forEach {
            check(Files.isDirectory(it, NOFOLLOW_LINKS) || Files.isRegularFile(it, NOFOLLOW_LINKS)) {
                "Harness distribution contains a link or special file: $it"
            }
        } }
        val library = distribution.resolve("lib")
        val actual = requireNotNull(library.listFiles()).map {
            check(Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS))
            it.name
        }.toSet()
        check(actual == classpath.toSet()) { "Harness JAR set differs from the lock" }
        val hashes = classpath.associateWith { name ->
            library.resolve(name).inputStream().use(::digest).also {
                check(it == values.getValue("sha256.$name")) { "Harness JAR checksum mismatch: $name" }
            }
        }
        val bench = values.getValue("benchJar")
        val classes = JarFile(library.resolve(bench)).use { jar ->
            jar.entries().asSequence().filter {
                !it.isDirectory && it.name.startsWith("com/eignex/klause/bench/") && it.name.endsWith(".class")
            }.map { entry -> entry.name to jar.getInputStream(entry).use(::digest) }.toList()
        }
        check(classes.isNotEmpty() && classes.map { it.first }.distinct().size == classes.size)
        val harnessFingerprint = fingerprint(classes.sortedBy { it.first })
        val libraries = classpath.filter { it != bench && !solverLibrary(it) }
            .mapIndexed { index, name -> "$index/$name" to hashes.getValue(name) }
        val libraryFingerprint = fingerprint(libraries)
        check(harnessFingerprint == values.getValue("harnessFingerprint")) { "Harness class fingerprint mismatch" }
        check(libraryFingerprint == values.getValue("libraryFingerprint")) { "Harness ordered library fingerprint mismatch" }
        var ancestor = receipt.toPath().toAbsolutePath().normalize()
        while (true) {
            check(!Files.isSymbolicLink(ancestor))
            ancestor = ancestor.parent ?: break
        }
        val parent = receipt.absoluteFile.parentFile.toPath()
        Files.createDirectories(parent)
        val temporary = Files.createTempDirectory(parent, ".harness-validation-").toFile()
        try {
            temporary.resolve("lock.properties").writeText(values.entries.sortedBy { it.key }
                .joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
            temporary.resolve("classpath.txt").writeText(classpath.joinToString("\n", postfix = "\n") { "lib/$it" })
            temporary.resolve("artifacts.sha256").writeText(hashes.entries.joinToString("\n", postfix = "\n") {
                "${it.value}  lib/${it.key}"
            })
            temporary.resolve("verification.properties").writeText(
                "format=1\nrevision=$revision\nclassCount=${classes.size}\n" +
                    "harnessFingerprint=$harnessFingerprint\nlibraryFingerprint=$libraryFingerprint\n",
            )
            if (receipt.exists()) {
                check(Files.isDirectory(receipt.toPath(), NOFOLLOW_LINKS))
                Files.walk(receipt.toPath()).use { paths -> paths.forEach {
                    check(Files.isDirectory(it, NOFOLLOW_LINKS) || Files.isRegularFile(it, NOFOLLOW_LINKS))
                } }
                check(receipt.deleteRecursively())
            }
            Files.move(temporary.toPath(), receipt.toPath(), ATOMIC_MOVE)
        } finally {
            temporary.deleteRecursively()
        }
    }

    companion object {
        /** Read a strict source lock without materializing or building its revision. */
        fun read(file: File): FrozenHarnessLock {
            check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Harness lock is absent or symlinked: $file" }
            val entries = file.readLines().filter { it.isNotBlank() }.map { line ->
                val parts = line.split('=', limit = 2)
                require(parts.size == 2 && parts.all { it.isNotBlank() && it == it.trim() })
                parts[0] to parts[1]
            }
            require(entries.map { it.first }.distinct().size == entries.size) { "Duplicate harness lock key" }
            val values = entries.toMap()
            require(values["format"] == "1")
            require(values.getValue("revision").matches(Regex("[a-f0-9]{40}")))
            val jars = values.getValue("classpath").split(',')
            require(jars.isNotEmpty() && jars.distinct().size == jars.size)
            require(jars.all { it.matches(Regex("[A-Za-z0-9._-]+\\.jar")) })
            require(values.getValue("benchJar") in jars)
            val fixed = setOf("format", "revision", "benchJar", "classpath", "harnessFingerprint", "libraryFingerprint")
            require(values.keys == fixed + jars.map { "sha256.$it" }) { "Unexpected or missing harness lock key" }
            require((listOf("harnessFingerprint", "libraryFingerprint") + jars.map { "sha256.$it" }).all {
                values.getValue(it).matches(Regex("[a-f0-9]{64}"))
            })
            return FrozenHarnessLock(values)
        }

        private fun digest(stream: InputStream): String {
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
            return HexFormat.of().formatHex(hash.digest())
        }

        private fun fingerprint(entries: List<Pair<String, String>>): String {
            val json = entries.joinToString(",\n", prefix = "{\n", postfix = "\n}") { (name, hash) ->
                require(name.none { it.code < 32 })
                val escaped = name.replace("\\", "\\\\").replace("\"", "\\\"")
                "    \"$escaped\": \"$hash\""
            }
            return json.byteInputStream().use(::digest)
        }

        private fun solverLibrary(name: String): Boolean =
            (name.startsWith("klause-jvm-") || name.startsWith("klause-cli-jvm-")) && name.endsWith(".jar")
    }
}
