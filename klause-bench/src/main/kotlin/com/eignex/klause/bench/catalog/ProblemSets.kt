package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File

/** One problem of a named set: the suite that has it and its name there, as `select` prints them. */
internal data class SetEntry(val suite: String, val problem: String)

/**
 * Named problem sets: fixed lists in `klause-bench/sets/<name>.txt`, so benches that name one run the same problems
 * whatever the corpus or the reference results hold. A line is `<suite>/<problem>`, `@<set>` includes another set,
 * and `#` starts a comment.
 */
internal object ProblemSets {
    fun dir(): File = File(CorpusFetcher.workspaceRoot(), "klause-bench/sets")

    /** Every set's name. */
    fun names(root: File = dir()): List<String> =
        root.listFiles { f -> f.extension == "txt" }.orEmpty().map { it.nameWithoutExtension }.sorted()

    /** The problems of the sets [names], in file order, each once. */
    fun load(names: List<String>, root: File = dir()): List<SetEntry> {
        val entries = LinkedHashSet<SetEntry>()
        fun read(name: String, seen: List<String>) {
            require(name !in seen) { "set '$name' includes itself: ${(seen + name).joinToString(" -> ")}" }
            val file = File(root, "$name.txt")
            require(file.isFile) { "no set '$name' in $root; sets: ${names(root).joinToString()}" }
            for (raw in file.readLines()) {
                val line = raw.substringBefore('#').trim()
                when {
                    line.isEmpty() -> Unit

                    line.startsWith("@") -> read(line.drop(1).trim(), seen + name)

                    else -> {
                        require('/' in line) { "set '$name': '$line' is not <suite>/<problem>" }
                        entries += SetEntry(line.substringBefore('/'), line.substringAfter('/'))
                    }
                }
            }
        }
        for (name in names) read(name, emptyList())
        return entries.toList()
    }
}
