package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.SolveEvidence
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.jar.JarFile

internal object CliPreparationRuntime {
    val launcherOptions = listOf("-XX:TieredStopAtLevel=1", "--enable-native-access=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector")

    fun requireLauncher(launcher: File) {
        val expected = "DEFAULT_JVM_OPTS='" + launcherOptions.joinToString(" ") { "\"$it\"" } + "'"
        require(launcher.readLines().single { it.startsWith("DEFAULT_JVM_OPTS=") } == expected) {
            "CLI launcher options changed; preparation observer requires explicit adaptation"
        }
    }

    fun classpath(cli: File, bench: File): List<File> {
        val cliJars = requireNotNull(cli.listFiles()).filter { it.extension == "jar" }.sortedBy { it.name }
        require(cliJars.count { it.name.startsWith("annotations-") } == 1)
        // The helper uses the CLI annotation runtime; compiler dependencies belong to the bench parent.
        val benchJars = requireNotNull(bench.listFiles()).filter {
            it.extension == "jar" && !it.name.startsWith("kotlin-compiler-embeddable-") &&
                !it.name.startsWith("annotations-")
        }.sortedBy { it.name }
        require(cliJars.isNotEmpty() && benchJars.isNotEmpty())
        val cliByName = cliJars.associateBy { it.name }
        for (jar in benchJars) {
            cliByName[jar.name]?.let { authoritative ->
                require(SolveEvidence.fileHashes(mapOf("jar" to jar)) ==
                    SolveEvidence.fileHashes(mapOf("jar" to authoritative))) { "shared JAR differs: ${jar.name}" }
            }
        }
        return (cliJars + benchJars.filter { it.name !in cliByName }).also(::requireSharedClasses)
    }

    internal fun requireSharedClasses(jars: List<File>) {
        val classes = mutableMapOf<String, File>()
        for (file in jars) {
            JarFile(file).use { jar ->
                for (entry in jar.entries()) {
                    if (!entry.name.endsWith(".class") || entry.name.endsWith("module-info.class")) continue
                    val previous = classes.putIfAbsent(entry.name, file) ?: continue
                    JarFile(previous).use { original ->
                        val expected = original.getInputStream(original.getJarEntry(entry.name)).use { it.readBytes() }
                        val actual = jar.getInputStream(entry).use { it.readBytes() }
                        require(expected.contentEquals(actual)) { "shared class differs: ${entry.name}" }
                    }
                }
            }
        }
    }

    fun identity(jars: List<File>): JsonObject = buildJsonObject {
        jars.forEachIndexed { index, file ->
            put("$index/${file.name}", JsonPrimitive(SolveEvidence.fileHashes(mapOf("jar" to file)).getValue("jar")))
        }
    }
}
