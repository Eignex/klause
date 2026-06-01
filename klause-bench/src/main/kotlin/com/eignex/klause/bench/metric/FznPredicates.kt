package com.eignex.klause.bench.metric

import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File

/**
 * Shared FlatZinc-predicate analysis used by the coverage and compile-audit metrics: count
 * the constraint predicates in a `.fzn`, and load the set klause handles natively (the
 * `redefinitions.mzn` declarations plus the names the `klause-fzn-cli` parser dispatches on).
 */
object FznPredicates {
    private val constraintHead = Regex("""^\s*constraint\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

    fun counts(fznFile: File): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        fznFile.useLines { lines ->
            for (line in lines) constraintHead.find(line)?.let { out.merge(it.groupValues[1], 1) { a, _ -> a + 1 } }
        }
        return out
    }

    val nativeSet: Set<String> by lazy { loadNativeSet() }

    private fun loadNativeSet(): Set<String> {
        val root = CorpusFetcher.workspaceRoot()
        val redef = File(root, "klause-mzn-lib/share/minizinc/klause/redefinitions.mzn")
        val predicateDecl = Regex("""^\s*predicate\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val fromRedef = buildSet {
            if (redef.isFile) redef.useLines { ls -> ls.forEach { l -> predicateDecl.find(l)?.let { add(it.groupValues[1]) } } }
        }
        val constraintsKt = File(root, "klause/src/commonMain/kotlin/com/eignex/klause/formats/flatzinc/FlatZincConstraints.kt")
        val nameLit = Regex("""\"([A-Za-z_][A-Za-z0-9_]*)\"""")
        val fromParser = buildSet {
            if (constraintsKt.isFile) constraintsKt.useLines { ls ->
                for (l in ls) if ("->" in l) for (m in nameLit.findAll(l)) if (m.groupValues[1].length > 2) add(m.groupValues[1])
            }
        }
        return fromRedef + fromParser
    }
}
