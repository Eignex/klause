package com.eignex.klause.bench.metric

import com.eignex.klause.bench.metric.BooleanSourceChecker.Answer
import com.eignex.klause.bench.metric.BooleanSourceChecker.Clause
import com.eignex.klause.bench.metric.BooleanSourceChecker.Cnf
import com.eignex.klause.bench.metric.BooleanSourceChecker.Literal
import com.eignex.klause.bench.metric.BooleanSourceChecker.Opb
import com.eignex.klause.bench.metric.BooleanSourceChecker.Query
import com.eignex.klause.bench.metric.BooleanSourceChecker.Row
import com.eignex.klause.bench.metric.BooleanSourceChecker.Term
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import kotlin.math.abs

/** Renders a [Query] as one SMT-LIB satisfiability problem over Boolean variables `x1..xn` and integer sums. */
internal object BooleanSourceQuery {
    const val SOLVER_TIMEOUT_MS = 10_000

    fun write(query: Query, out: Appendable) {
        out.append("(set-option :timeout ").append(SOLVER_TIMEOUT_MS.toString()).append(")\n")
        for (index in 1..query.source.size) out.append("(declare-const x").append(index.toString()).append(" Bool)\n")
        when (val source = query.source) {
            is Cnf -> {
                for (clause in source.clauses) {
                    if (clause.weight == null) out.append("(assert ").clause(clause).append(")\n")
                }
                query.costBelow?.let { bound ->
                    out.append("(assert (< ")
                        .sum(source.clauses.filter { it.weight != null }) { clause ->
                            append("(ite ").clause(clause).append(" 0 ")
                                .integer(checkNotNull(clause.weight)).append(')')
                        }
                        .append(' ').integer(bound).append("))\n")
                }
            }
            is Opb -> {
                for (row in source.rows) out.append("(assert ").relation(row).append(")\n")
                val soft = source.softRows.isNotEmpty()
                if (soft) {
                    out.append("(define-fun soft () Int ")
                        .sum(source.softRows) { row ->
                            append("(ite ").relation(row).append(" 0 ")
                                .integer(checkNotNull(row.softWeight)).append(')')
                        }
                        .append(")\n")
                    source.top?.let { top -> out.append("(assert (< soft ").integer(top).append("))\n") }
                }
                query.costBelow?.let { bound ->
                    out.append("(assert (< ")
                    when {
                        !soft -> out.terms(source.objective.orEmpty())
                        source.objective.isNullOrEmpty() -> out.append("soft")
                        else -> out.append("(+ ").terms(source.objective).append(" soft)")
                    }
                    out.append(' ').integer(bound).append("))\n")
                }
            }
        }
        out.append("(check-sat)\n")
    }

    private fun Appendable.literal(index: Int, negated: Boolean): Appendable =
        if (negated) append("(not x").append(index.toString()).append(')') else append('x').append(index.toString())

    private fun Appendable.clause(clause: Clause): Appendable = when (clause.literals.size) {
        0 -> append("false")
        1 -> clause.literals[0].let { literal(abs(it), it < 0) }
        else -> {
            append("(or")
            for (signed in clause.literals) append(' ').literal(abs(signed), signed < 0)
            append(')')
        }
    }

    private fun Appendable.product(product: List<Literal>): Appendable = if (product.size == 1) {
        literal(product[0].index, product[0].negated)
    } else {
        append("(and")
        for (factor in product) append(' ').literal(factor.index, factor.negated)
        append(')')
    }

    private fun Appendable.terms(terms: List<Term>): Appendable = sum(terms) { term ->
        append("(ite ").product(term.product).append(' ').integer(term.coefficient).append(" 0)")
    }

    private fun Appendable.relation(row: Row): Appendable =
        append('(').append(row.relation.symbol).append(' ').terms(row.terms).append(' ').integer(row.rhs).append(')')

    private fun <T> Appendable.sum(items: List<T>, item: Appendable.(T) -> Unit): Appendable {
        when (items.size) {
            0 -> append('0')
            1 -> item(items[0])
            else -> {
                append("(+")
                for (each in items) {
                    append(' ')
                    item(each)
                }
                append(')')
            }
        }
        return this
    }

    private fun Appendable.integer(value: BigInteger): Appendable = if (value.signum() < 0) {
        append("(- ").append(value.negate().toString()).append(')')
    } else {
        append(value.toString())
    }
}

/**
 * Decides [Query]s with the pinned Z3 release, or the executable configured by `KLAUSE_Z3` and
 * `KLAUSE_Z3_SHA256`, under the resource controls of [SmtSourceReceipt]. The binary hash is
 * verified around every query and recorded in [identity].
 */
internal class Z3BooleanProver : BooleanSourceChecker.Prover, AutoCloseable {
    private val directory: File = Files.createTempDirectory("boolean-source-check-").toFile()
    private val resolution = lazy {
        SmtSourceTools.resolve(SmtSourceValidation.REFUTATION, File(directory, "tool")).also { it.verify() }
    }
    private val tool by resolution

    override val identity: Map<String, String>
        get() = buildMap {
            put("checker", "z3")
            put("executableSha256", tool.binarySha256)
            tool.artifactUrl?.let { put("artifactUrl", it) }
            tool.artifactSha256?.let { put("artifactSha256", it) }
            put("solverTimeoutMs", BooleanSourceQuery.SOLVER_TIMEOUT_MS.toString())
        }

    override fun decide(query: Query): Answer {
        val work = Files.createTempDirectory(directory.toPath(), "query-").toFile()
        try {
            val problem = File(work, "query.smt2")
            problem.bufferedWriter().use { BooleanSourceQuery.write(query, it) }
            val input = File(work, "stdin").apply { writeBytes(ByteArray(0)) }
            tool.verify()
            val process = SmtSourceReceipt.run(
                listOf(tool.executable.absolutePath, "-smt2", "-T:$HARD_LIMIT_SECONDS", "-memory:4096",
                    problem.absolutePath),
                input, work, PROCESS_TIMEOUT_MS,
            )
            tool.verify()
            return interpret(process)
        } finally {
            work.deleteRecursively()
        }
    }

    override fun close() {
        try {
            if (resolution.isInitialized()) tool.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    companion object {
        private const val HARD_LIMIT_SECONDS = 20
        private const val PROCESS_TIMEOUT_MS = 25_000L

        /** Only a clean, resource-qualified run settles a query; anything else leaves it unknown. */
        fun interpret(process: SmtToolProcess): Answer {
            val stdout = process.stdout.trim()
            val clean = process.exit == 0 && !process.timedOut && process.cleanupComplete &&
                process.stderr.isBlank() && process.memory?.qualified() == true
            return when {
                clean && stdout == "unsat" -> Answer.Unsat
                clean && stdout == "sat" -> Answer.Sat
                process.timedOut -> Answer.Unknown("z3 process timed out")
                process.memory?.qualified() != true -> Answer.Unknown("z3 memory observation incomplete or exceeded")
                else -> Answer.Unknown("z3 exit ${process.exit}: ${stdout.ifEmpty { process.stderr.trim() }.take(200)}")
            }
        }
    }
}
