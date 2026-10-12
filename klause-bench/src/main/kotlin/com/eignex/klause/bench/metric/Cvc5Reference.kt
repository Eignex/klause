package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles

/**
 * SMT-LIB reference adapter for native cvc5, with `--tlimit` and [Z3Reference.parse].
 * Rows use solver key `cvc5`.
 */
internal object Cvc5Reference {
    /** The cvc5 executable, from `PATH` by default; override with `-Dklause.bench.cvc5=/path/to/cvc5`. */
    private val BINARY = System.getProperty("klause.bench.cvc5", "cvc5")

    fun available(): Boolean = NativeReference.available(BINARY)

    fun run(ref: ProblemRef, budget: Budget): SolverInvocation.Result =
        CorpusFiles.withPlainFile(CorpusFetcher.resolve(ref.source)) { file ->
            val cmd = listOf(BINARY, "--lang=smt2", "--tlimit=${budget.timeoutMillis}", file.absolutePath)
            val (stdout, elapsedMs) = NativeReference.exec(cmd, budget.timeoutMillis)
            Z3Reference.parse(stdout, elapsedMs, cmd)
        }
}
