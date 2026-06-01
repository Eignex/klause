package com.eignex.klause.bench.catalog

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Xor

/**
 * Catalog content: every [Suite] the bench knows about, grouped by where the instances come
 * from. This is the curated, in-source replacement for problems formerly pulled from
 * hard-coded `Portfolio` entries, classpath resources, and ad-hoc build-time downloads.
 *
 *  - [handwrittenCore] — small SAT/CSP instances built directly in Kotlin (`InCode`).
 *  - [dimacsCore]/[opbCore]/[schemaCore]/[flatzincCore] — vendored under `klause-bench/corpus/`.
 *  - [mznSmoke] — the in-tree `klause-mzn-lib/test-models/` smoke set (referenced, not copied).
 *  - external MiniZinc/SAT collections (fetched on demand) are declared in [ExternalCollections]
 *    and wired into suites in phase 2.
 */
object Suites {

    val all: List<Suite> by lazy {
        listOf(handwrittenCore, dimacsCore, opbCore, schemaCore, flatzincCore, smtlibCore, xcsp3Core, mznSmoke, satlibUf20)
    }

    // --- In-code SAT/CSP (ported from the former Portfolio) ---

    private val handwrittenCore = suite("handwritten-core", "Small hand-built SAT/CSP instances") {
        license = "internal"

        inCode("threeClauses", Category.SAT, Expected.Sat) {
            Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
            ))
        }
        inCode("cardXor", Category.SAT, Expected.Sat) {
            Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>(
                Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
                    min = 2, max = 3),
                Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), targetParity = 1),
            ))
        }
        inCode("pseudoBoolean", Category.SAT, Expected.Sat) {
            Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>(
                PseudoBoolean(
                    weights = intArrayOf(2, 3, 1, 1),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
                    op = PbOp.LE, bound = 3,
                ),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
            ))
        }
        inCode("linearLE", Category.CSP, Expected.Sat) {
            Problem(numBoolVars = 0, numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, 4),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
                    Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 2),
                ))
        }
        inCode("permutation3", Category.CSP, Expected.Sat) {
            Problem(numBoolVars = 0, numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
                factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)))
        }
        inCode("mixedBoolInt", Category.CSP, Expected.Sat) {
            Problem(numBoolVars = 2, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
                ))
        }
        inCode("cardinalityStress", Category.SAT, Expected.Sat) {
            Problem(numBoolVars = 8, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>(
                Cardinality((0..5).map { Lit.make(it, true) }.toIntArray(), min = 0, max = 3),
                Cardinality((0..7).map { Lit.make(it, true) }.toIntArray(), min = 3, max = 8),
                Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), min = 2, max = 2),
                Cardinality(intArrayOf(Lit.make(2, true), Lit.make(3, true)), min = 0, max = 1),
                Cardinality((0..7).map { Lit.make(it, true) }.toIntArray(), min = 3, max = 8),
                Cardinality((0..7).map { Lit.make(it, true) }.toIntArray(), min = 0, max = 5),
            ))
        }
        inCode("pbReifiedMix", Category.SAT, Expected.Sat) {
            Problem(numBoolVars = 6, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>(
                ReifiedCardinality(auxBoolVar = 4,
                    literals = intArrayOf(Lit.make(1, true), Lit.make(2, true)), min = 2, max = 2),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(4, true))),
                ReifiedCardinality(auxBoolVar = 5,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                    min = 1, max = 3),
                Clause(intArrayOf(Lit.make(3, false), Lit.make(5, true))),
                Cardinality(intArrayOf(
                    Lit.make(0, false), Lit.make(1, true), Lit.make(2, true), Lit.make(3, false),
                ), min = 0, max = 2),
                PseudoBoolean(
                    weights = intArrayOf(1, 2, 3, 4),
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
                    op = PbOp.GE, bound = 3,
                ),
            ))
        }
        inCode("smallRandom3sat", Category.SAT, Expected.Sat) {
            Problem(numBoolVars = 12, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(3, false), Lit.make(7, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(4, true), Lit.make(8, false))),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(5, false), Lit.make(9, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(6, true), Lit.make(10, false))),
                Clause(intArrayOf(Lit.make(3, true), Lit.make(7, false), Lit.make(11, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(5, true), Lit.make(9, false))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(4, false), Lit.make(11, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(8, true), Lit.make(10, true))),
                Clause(intArrayOf(Lit.make(6, false), Lit.make(7, true), Lit.make(8, false))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(4, true), Lit.make(5, true), Lit.make(6, true))),
                Clause(intArrayOf(Lit.make(7, false), Lit.make(9, false), Lit.make(11, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true), Lit.make(5, true))),
                Clause(intArrayOf(Lit.make(3, false), Lit.make(8, true), Lit.make(10, false))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(6, false), Lit.make(11, false))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(4, false), Lit.make(9, true))),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(7, true), Lit.make(10, false))),
                Clause(intArrayOf(Lit.make(3, true), Lit.make(5, false), Lit.make(8, false))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(6, true), Lit.make(11, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false), Lit.make(11, true))),
                Clause(intArrayOf(Lit.make(4, true), Lit.make(7, false), Lit.make(9, false))),
                Clause(intArrayOf(Lit.make(5, true), Lit.make(8, true), Lit.make(10, true))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(3, true), Lit.make(6, false))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(5, false), Lit.make(7, false))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(8, true), Lit.make(11, false))),
                Clause(intArrayOf(Lit.make(3, false), Lit.make(6, true), Lit.make(9, true))),
                Clause(intArrayOf(Lit.make(4, false), Lit.make(10, true), Lit.make(11, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(8, false))),
                Clause(intArrayOf(Lit.make(2, true), Lit.make(3, false), Lit.make(4, true))),
                Clause(intArrayOf(Lit.make(5, true), Lit.make(6, true), Lit.make(7, true))),
            ))
        }
        inCode("budgetCampaign", Category.ASSIGNMENT, Expected.Sat) {
            Problem(numBoolVars = 3, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 100)),
                factors = arrayOf<Factor>(
                    Cardinality.exactlyOne(intArrayOf(
                        Lit.make(0, true), Lit.make(1, true), Lit.make(2, true),
                    )),
                    ReifiedLinear(auxBoolVar = 0, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, 30),
                    ReifiedLinear(auxBoolVar = 1, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, 50),
                    ReifiedLinear(auxBoolVar = 2, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, 80),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 60),
                ))
        }

        inCode("clauseContradiction", Category.UNSAT, Expected.Unsat) {
            Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ))
        }
        inCode("intEqContradiction", Category.UNSAT, Expected.Unsat) {
            Problem(numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
                ))
        }
        inCode("pigeonhole", Category.UNSAT, Expected.Unsat) {
            Problem(numBoolVars = 0, numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 2)))
        }
    }

    // --- Vendored corpus suites (klause-bench/corpus/) ---

    private val dimacsCore = suite("dimacs-core", "Curated small DIMACS CNF (SAT + UNSAT)") {
        format = Format.DIMACS; license = "SATLIB-style (public benchmarks)"
        vendored("php4", Category.UNSAT, Expected.Unsat)
        vendored("random3sat-20-80", Category.SAT, Expected.Sat)
        vendored("random3sat-50-200", Category.SAT, Expected.Sat)
    }

    private val opbCore = suite("opb-core", "Curated pseudo-Boolean OPB") {
        format = Format.OPB; license = "internal"
        vendored("setcover-tiny", Category.PACKING, Expected.Sat)
    }

    private val schemaCore = suite("schema-core", "klause JSON schema instances") {
        format = Format.JSON_SCHEMA; license = "internal"
        vendored("campaign", Category.ASSIGNMENT, Expected.Sat)
    }

    private val flatzincCore = suite("flatzinc-core", "Curated small FlatZinc (satisfaction)") {
        format = Format.FLATZINC; license = "internal"
        vendored("cardinality", Category.CSP, Expected.Sat)
        vendored("permutation4", Category.CSP, Expected.Sat)
        vendored("small-linear", Category.CSP, Expected.Sat)
    }

    private val smtlibCore = suite("smtlib-core", "Curated SMT-LIB QF_LIA instances") {
        format = Format.SMTLIB_QF_LIA; license = "internal"
        vendored("lia-basic", Category.CSP, Expected.Sat)
        vendored("lia-opt", Category.OPTIMIZATION, Expected.Opt(7))
    }

    private val xcsp3Core = suite("xcsp3-core", "Curated XCSP3 integer CSP/COP instances") {
        format = Format.XCSP3; license = "internal"
        vendored("magic-series-tiny", Category.CSP, Expected.Sat, relPath = "xcsp3/magic-series-tiny.xml")
        vendored("sum-opt-tiny", Category.OPTIMIZATION, Expected.Unknown, relPath = "xcsp3/sum-opt-tiny.xml")
    }

    // --- In-tree MiniZinc smoke set (referenced from klause-mzn-lib/test-models/) ---

    // --- External SAT collection (auto-fetched SATLIB tarball) ---

    private val satlibUf20 = suite("satlib-uf20", "SATLIB uf20-91 SAT instances (auto-fetched sample)") {
        // The full set is 1000 instances; reference a small, stable sample so the suite is
        // usable out of the box. CorpusFetcher downloads the tarball on first resolve.
        format = Format.DIMACS
        val col = ExternalCollections.satlibUf20
        // Tarball names instances `uf20-0<n>.cnf` (raw, unpadded n). Reference a small sample.
        for (n in 1..5) {
            external("uf20-$n", col, "uf20-0$n.cnf", Category.SAT, Expected.Sat)
        }
    }

    private val mznSmoke = suite("mzn-smoke", "Mandatory MiniZinc smoke models (CI parity)") {
        format = Format.MINIZINC; license = "internal"
        val base = "klause-mzn-lib/test-models"
        workspace("graph_coloring", "$base/graph_coloring.mzn", Category.CSP, Expected.Sat)
        workspace("latin_square", "$base/latin_square.mzn", Category.CSP, Expected.Sat)
        workspace("magic_square", "$base/magic_square.mzn", Category.CSP, Expected.Sat)
        workspace("queens", "$base/queens.mzn", Category.CSP, Expected.Sat)
        workspace("send_more_money", "$base/send_more_money.mzn", Category.CSP, Expected.Sat)
        workspace("sudoku", "$base/sudoku.mzn", Category.CSP, Expected.Sat)
        workspace("zero_one_knapsack", "$base/zero_one_knapsack.mzn", Category.PACKING, Expected.Unknown)
    }
}

/**
 * Non-vendored problem collections, fetched on demand by `source.CorpusFetcher`. Declared
 * here so the "where did this come from / why isn't it in the repo" answer is auditable.
 * Wired into suites in phase 2.
 */
object ExternalCollections {
    val minizincBenchmarks = ExternalCollection(
        id = "minizinc-benchmarks",
        url = "https://github.com/MiniZinc/minizinc-benchmarks.git",
        license = "GPLv3 + per-problem licenses",
        reason = "GPLv3 — redistribution outside a fetch cache is restricted",
        fetch = FetchMethod.GitClone(depth = 1),
    )
    val libminizincTests = ExternalCollection(
        id = "libminizinc-tests",
        url = "https://github.com/MiniZinc/libminizinc.git",
        license = "MPL-2.0",
        reason = "owned by MiniZinc CI; tracked upstream rather than copied",
        fetch = FetchMethod.GitClone(depth = 1),
    )
    val hakank = ExternalCollection(
        id = "hakank",
        url = "https://github.com/hakank/hakank.git",
        license = "GPL (per-file varies)",
        reason = "large (~1GB); sparse-checkout of the minizinc/ subtree only",
        fetch = FetchMethod.GitClone(depth = 1, sparsePath = "minizinc"),
    )
    val satlibUf20 = ExternalCollection(
        id = "satlib-uf20-91",
        url = "https://www.cs.ubc.ca/~hoos/SATLIB/Benchmarks/SAT/RND3SAT/uf20-91.tar.gz",
        license = "SATLIB (public benchmarks)",
        reason = "1000-instance set; fetched rather than vendored wholesale",
        fetch = FetchMethod.Tarball,
    )
    val satlibUuf50 = ExternalCollection(
        id = "satlib-uuf50-218",
        url = "https://www.cs.ubc.ca/~hoos/SATLIB/Benchmarks/SAT/RND3SAT/uuf50-218.tar.gz",
        license = "SATLIB (public benchmarks)",
        reason = "1000-instance set; fetched rather than vendored wholesale",
        fetch = FetchMethod.Tarball,
    )

    val all = listOf(minizincBenchmarks, libminizincTests, hakank, satlibUf20, satlibUuf50)
}
