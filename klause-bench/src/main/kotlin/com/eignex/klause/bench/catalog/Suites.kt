package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.source.LibminizincExpected
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem

/**
 * Catalog content: every [Suite] the bench knows about, grouped by where the instances come
 * from.
 *
 *  - [handwrittenCore] — small SAT/CSP instances built directly in Kotlin (`InCode`).
 *  - [dimacsCore]/[opbCore]/[schemaCore] — vendored under `klause-bench/smoke-corpus/`.
 *  - [mznSmoke] — the in-tree `klause-mzn-lib/test-models/` smoke set (referenced, not copied).
 *  - external MiniZinc/SAT collections (fetched on demand) are declared in [ExternalCollections].
 */
internal object Suites {

    val all: List<Suite> by lazy {
        listOf(
            handwrittenCore, slackAllDifferent, dimacsCore, wcnfCore, opbCore, schemaCore,
            smtlibCore, xcsp3Core, mpsCore, mznSmoke, satlibUf20, satLadder, satCrafted,
        )
    }

    /** Discovered-on-demand suites over fetched external corpora. The provider runs the
     *  [com.eignex.klause.bench.source.CorpusSelection] machinery (per-family caps / sampling
     *  via `-Dklause.bench.select.*`) and fetches the collection on first resolve. */
    val dynamic: List<DynamicSuite> by lazy {
        listOf(
            DynamicSuite("mzn-bench", "MiniZinc Challenge benchmarks (fetched; 1/family by default)") {
                CorpusSelection.select(
                    ExternalCollections.minizincBenchmarks,
                    CorpusSelection.Layout.MznChallenge(),
                    CorpusSelection.Selection.fromProps(defaultPerFamily = 1),
                    Category.OPTIMIZATION,
                )
            },
            DynamicSuite("libminizinc-tests", "libminizinc compiler test suite (fetched; 1/family by default)") {
                CorpusSelection.select(
                    ExternalCollections.libminizincTests,
                    CorpusSelection.Layout.FlatMzn("tests/spec/unit"),
                    CorpusSelection.Selection.fromProps(defaultPerFamily = 1),
                    Category.CSP,
                    expected = { LibminizincExpected.parse(it) },
                )
            },
            DynamicSuite("hakank", "hakank MiniZinc collection (fetched; 1/family by default)") {
                CorpusSelection.select(
                    ExternalCollections.hakank,
                    CorpusSelection.Layout.FlatMzn("minizinc"),
                    CorpusSelection.Selection.fromProps(defaultPerFamily = 1),
                    Category.CSP,
                )
            },
            DynamicSuite(
                "smtlib-qflia",
                "SMT-LIB QF_LIA non-incremental set (fetched, full ~13k .smt2; cap with per-family=/max=)",
            ) {
                CorpusSelection.select(
                    ExternalCollections.smtlibQfLia,
                    CorpusSelection.Layout.Flat("non-incremental/QF_LIA", "smt2"),
                    CorpusSelection.Selection.fromProps(),
                    Category.CSP,
                    format = Format.SMTLIB_QF_LIA,
                )
            },
            DynamicSuite(
                "smtlib-qflra",
                "SMT-LIB QF_LRA non-incremental set (fetched, ~1.7k .smt2; cap with per-family=/max=)",
            ) {
                CorpusSelection.select(
                    ExternalCollections.smtlibQfLra,
                    CorpusSelection.Layout.Flat("non-incremental/QF_LRA", "smt2"),
                    CorpusSelection.Selection.fromProps(),
                    Category.CSP,
                    format = Format.SMTLIB_QF_LIA,
                )
            },
            DynamicSuite(
                "smtlib-qflira",
                "SMT-LIB QF_LIRA non-incremental set (fetched, small mixed int/real .smt2)",
            ) {
                CorpusSelection.select(
                    ExternalCollections.smtlibQfLira,
                    CorpusSelection.Layout.Flat("non-incremental/QF_LIRA", "smt2"),
                    CorpusSelection.Selection.fromProps(),
                    Category.CSP,
                    format = Format.SMTLIB_QF_LIA,
                )
            },
            DynamicSuite(
                "miplib2017",
                "MIPLIB 2017 collection (fetched per-instance, ~1065 .mps; cap with max=/per-family=)",
            ) {
                CorpusSelection.select(
                    ExternalCollections.miplib2017,
                    CorpusSelection.Layout.Flat("", "mps"),
                    CorpusSelection.Selection.fromProps(),
                    Category.OPTIMIZATION,
                    format = Format.MPS,
                )
            },
            DynamicSuite(
                "dimacs-classic",
                "SATLIB classic structured DIMACS CNF — diverse, small Boolean set " +
                    "(aim/jnh/dubois/parity/inductive-inference/pigeon-hole/all-interval)",
            ) {
                ExternalCollections.dimacsClassic.flatMap { (col, category) ->
                    CorpusSelection.select(
                        col,
                        CorpusSelection.Layout.Flat("", "cnf"),
                        CorpusSelection.Selection.fromProps(),
                        category,
                        format = Format.DIMACS,
                    )
                }
            },
            DynamicSuite("pb-comp", "Pseudo-Boolean Competition 2024 selected OPB set (fetched; 1/family by default)") {
                // The archive ships both `.opb.xz` (linear + non-linear PB) and `.wbo.xz` (soft
                // constraints); this layout takes the `.opb` instances, `pb-comp-wbo` the `.wbo`.
                CorpusSelection.select(
                    ExternalCollections.pbComp2024,
                    CorpusSelection.Layout.Flat("PB24", "opb", familyOf = { it.substringBeforeLast('/', it) }),
                    CorpusSelection.Selection.fromProps(defaultPerFamily = 1),
                    Category.OPTIMIZATION,
                    format = Format.OPB,
                )
            },
            DynamicSuite("pb-comp-wbo", "Pseudo-Boolean Competition 2024 WBO soft-constraint set (fetched; 1/family)") {
                CorpusSelection.select(
                    ExternalCollections.pbComp2024,
                    CorpusSelection.Layout.Flat("PB24", "wbo", familyOf = { it.substringBeforeLast('/', it) }),
                    CorpusSelection.Selection.fromProps(defaultPerFamily = 1),
                    Category.OPTIMIZATION,
                    format = Format.OPB,
                )
            },
            DynamicSuite(
                "pb07-opb",
                "PB'07 native pseudo-Boolean OPB benchmarks (fetched, 487 crafted instances; " +
                    "optimization/decision, linear + non-linear)",
            ) {
                CorpusSelection.select(
                    ExternalCollections.pb07Other,
                    CorpusSelection.Layout.Flat(
                        "normalized-PB07",
                        "opb",
                        familyOf = { it.substringBeforeLast('/', it) },
                    ),
                    CorpusSelection.Selection.fromProps(),
                    Category.OPTIMIZATION,
                    format = Format.OPB,
                )
            },
            DynamicSuite("xcsp3-cop", "XCSP3 competition COP aggregate 2022-25 (fetched, 1000; cap with max=)") {
                CorpusSelection.select(
                    ExternalCollections.xcsp3Cop,
                    CorpusSelection.Layout.Flat("COP22to25", "xml", familyOf = ::xcspSeries),
                    CorpusSelection.Selection.fromProps(),
                    Category.OPTIMIZATION,
                    format = Format.XCSP3,
                )
            },
            DynamicSuite("xcsp3-csp", "XCSP3 competition CSP aggregate 2022-25 (fetched, 800; cap with max=)") {
                CorpusSelection.select(
                    ExternalCollections.xcsp3Csp,
                    CorpusSelection.Layout.Flat("CSP22to25", "xml", familyOf = ::xcspSeries),
                    CorpusSelection.Selection.fromProps(),
                    Category.CSP,
                    format = Format.XCSP3,
                )
            },
            DynamicSuite("maxsat-unweighted", "MaxSAT Evaluation 2024 exact unweighted track (fetched; 1/family)") {
                CorpusSelection.select(
                    ExternalCollections.maxsatExactUnweighted,
                    CorpusSelection.Layout.Flat("", "wcnf", familyOf = ::maxsatFamily),
                    CorpusSelection.Selection.fromProps(defaultPerFamily = 1),
                    Category.OPTIMIZATION,
                    format = Format.WCNF,
                )
            },
            DynamicSuite("maxsat-weighted", "MaxSAT Evaluation 2024 exact weighted track (fetched; 1/family)") {
                CorpusSelection.select(
                    ExternalCollections.maxsatExactWeighted,
                    CorpusSelection.Layout.Flat("", "wcnf", familyOf = ::maxsatFamily),
                    CorpusSelection.Selection.fromProps(defaultPerFamily = 1),
                    Category.OPTIMIZATION,
                    format = Format.WCNF,
                )
            },
        )
    }

    // --- Slack all_different (Hall-prone; for explanation / clause-learning A/B) ---

    /**
     * Golomb-ruler feasibility built directly as a [Problem]: marks 0 = m0 < m1 < ... < m,
     * with the C(m,2) pairwise differences channeled into aux int vars (domain `1..maxLen`)
     * and constrained all_different. Because `maxLen` >> C(m,2) the all_different is *slack*
     * (more values than vars ⇒ strict Hall sub-sets / multiple Régin SCCs), unlike the tight
     * n-vars/n-values all_differents in latin_square/sudoku. That's exactly the regime where a
     * responsible-subset (Hall-set) conflict explanation can differ from an all-vars one, so
     * this suite is the discriminating workload for that sharpening. `maxLen >= optimal` ⇒ SAT.
     */
    private fun golomb(m: Int, maxLen: Int): Problem {
        val pairs = buildList { for (i in 0 until m) for (j in i + 1 until m) add(i to j) }
        val nDiffs = pairs.size
        val numInt = m + nDiffs
        val domains = Array(numInt) { idx ->
            when {
                idx == 0 -> IntDomain(0, 0)

                // mark[0] pinned to 0
                idx < m -> IntDomain(0, maxLen.toLong())

                // marks
                else -> IntDomain(1, maxLen.toLong()) // differences
            }
        }
        val factors = ArrayList<Factor>()
        // Strictly increasing marks: mark[i] - mark[i+1] <= -1.
        for (i in 0 until m - 1) factors.add(Linear(intArrayOf(1, -1), intArrayOf(i, i + 1), LinearOp.LE, -1))
        // Difference channeling: mark[j] - mark[i] - d = 0.
        pairs.forEachIndexed { k, (i, j) ->
            factors.add(Linear(intArrayOf(1, -1, -1), intArrayOf(j, i, m + k), LinearOp.EQ, 0))
        }
        // All differences distinct — the slack all_different under test.
        factors.add(AllDifferent(IntArray(nDiffs) { m + it }, domainMin = 1, domainSize = maxLen))
        // Symmetry break: first gap shorter than last (d_first - d_last <= -1).
        factors.add(Linear(intArrayOf(1, -1), intArrayOf(m, m + nDiffs - 1), LinearOp.LE, -1))
        return Problem(numBoolVars = 0, numIntVars = numInt, intDomains = domains, factors = factors.toTypedArray())
    }

    private val slackAllDifferent = suite("slack-alldiff", "Slack all_different (Golomb rulers; Hall-prone)") {
        license = "internal"
        // (m, maxLen) with maxLen >= optimal ruler length ⇒ satisfiable; a spread of slack/size.
        for ((m, maxLen) in listOf(6 to 17, 6 to 20, 7 to 25, 7 to 30, 7 to 40, 8 to 45, 8 to 50)) {
            inCode("golomb_${m}_$maxLen", Category.CSP, Expected.Sat) { golomb(m, maxLen) }
        }
    }

    // --- In-code SAT/CSP ---

    private val handwrittenCore = suite("handwritten-core", "Small hand-built SAT/CSP instances") {
        license = "internal"

        inCode("threeClauses", Category.SAT, Expected.Sat) {
            Problem(
                numBoolVars = 4,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false))),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true), Lit.make(3, true))),
                    Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
                ),
            )
        }
        inCode("cardXor", Category.SAT, Expected.Sat) {
            Problem(
                numBoolVars = 4,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Cardinality(
                        intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
                        min = 2,
                        max = 3,
                    ),
                    Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), targetParity = 1),
                ),
            )
        }
        inCode("pseudoBoolean", Category.SAT, Expected.Sat) {
            Problem(
                numBoolVars = 4,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    PseudoBoolean(
                        weights = longArrayOf(2, 3, 1, 1),
                        literals = intArrayOf(
                            Lit.make(0, true),
                            Lit.make(1, true),
                            Lit.make(2, true),
                            Lit.make(3, true),
                        ),
                        op = PbOp.LE,
                        bound = 3,
                    ),
                    Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
                ),
            )
        }
        inCode("linearLE", Category.CSP, Expected.Sat) {
            Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, 4),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
                    Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 2),
                ),
            )
        }
        inCode("permutation3", Category.CSP, Expected.Sat) {
            Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
                factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
            )
        }
        inCode("mixedBoolInt", Category.CSP, Expected.Sat) {
            Problem(
                numBoolVars = 2,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
                ),
            )
        }
        inCode("cardinalityStress", Category.SAT, Expected.Sat) {
            Problem(
                numBoolVars = 8,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Cardinality((0..5).map { Lit.make(it, true) }.toIntArray(), min = 0, max = 3),
                    Cardinality((0..7).map { Lit.make(it, true) }.toIntArray(), min = 3, max = 8),
                    Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), min = 2, max = 2),
                    Cardinality(intArrayOf(Lit.make(2, true), Lit.make(3, true)), min = 0, max = 1),
                    Cardinality((0..7).map { Lit.make(it, true) }.toIntArray(), min = 3, max = 8),
                    Cardinality((0..7).map { Lit.make(it, true) }.toIntArray(), min = 0, max = 5),
                ),
            )
        }
        inCode("pbReifiedMix", Category.SAT, Expected.Sat) {
            Problem(
                numBoolVars = 6,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    ReifiedCardinality(
                        auxBoolVar = 4,
                        literals = intArrayOf(Lit.make(1, true), Lit.make(2, true)),
                        min = 2,
                        max = 2,
                    ),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(4, true))),
                    ReifiedCardinality(
                        auxBoolVar = 5,
                        literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                        min = 1,
                        max = 3,
                    ),
                    Clause(intArrayOf(Lit.make(3, false), Lit.make(5, true))),
                    Cardinality(
                        intArrayOf(
                            Lit.make(0, false),
                            Lit.make(1, true),
                            Lit.make(2, true),
                            Lit.make(3, false),
                        ),
                        min = 0,
                        max = 2,
                    ),
                    PseudoBoolean(
                        weights = longArrayOf(1, 2, 3, 4),
                        literals = intArrayOf(
                            Lit.make(0, true),
                            Lit.make(1, true),
                            Lit.make(2, true),
                            Lit.make(3, true),
                        ),
                        op = PbOp.GE,
                        bound = 3,
                    ),
                ),
            )
        }
        inCode("smallRandom3sat", Category.SAT, Expected.Sat) {
            Problem(
                numBoolVars = 12,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
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
                ),
            )
        }
        inCode("budgetCampaign", Category.ASSIGNMENT, Expected.Sat) {
            Problem(
                numBoolVars = 3,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 100)),
                factors = arrayOf<Factor>(
                    Cardinality.exactlyOne(
                        intArrayOf(
                            Lit.make(0, true),
                            Lit.make(1, true),
                            Lit.make(2, true),
                        ),
                    ),
                    ReifiedLinear(auxBoolVar = 0, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, 30),
                    ReifiedLinear(auxBoolVar = 1, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, 50),
                    ReifiedLinear(auxBoolVar = 2, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, 80),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 60),
                ),
            )
        }

        inCode("clauseContradiction", Category.UNSAT, Expected.Unsat) {
            Problem(
                numBoolVars = 1,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, true))),
                    Clause(intArrayOf(Lit.make(0, false))),
                ),
            )
        }
        inCode("intEqContradiction", Category.UNSAT, Expected.Unsat) {
            Problem(
                numBoolVars = 0,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
                ),
            )
        }
        inCode("pigeonhole", Category.UNSAT, Expected.Unsat) {
            Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 2)),
            )
        }
    }

    // --- Vendored smoke-corpus suites (klause-bench/smoke-corpus/) ---

    private val dimacsCore = suite("dimacs-core", "Curated small DIMACS CNF (SAT + UNSAT)") {
        format = Format.DIMACS
        license = "SATLIB-style (public benchmarks)"
        vendored("php4", Category.UNSAT, Expected.Unsat)
        vendored("php3", Category.UNSAT, Expected.Unsat)
        vendored("implication-chain", Category.SAT, Expected.Sat)
        vendored("bipartite-2col", Category.SAT, Expected.Sat)
        vendored("random3sat-20-80", Category.SAT, Expected.Sat)
        vendored("random3sat-50-200", Category.SAT, Expected.Sat)
    }

    private val wcnfCore = suite("wcnf-core", "Curated DIMACS WCNF (MaxSAT)") {
        format = Format.WCNF
        license = "internal"
        vendored("maxsat-tiny", Category.OPTIMIZATION, Expected.Opt(1L))
    }

    private val opbCore = suite("opb-core", "Curated pseudo-Boolean OPB") {
        format = Format.OPB
        license = "internal"
        vendored("setcover-tiny", Category.PACKING, Expected.Sat)
        vendored("pb-cardinality", Category.SAT, Expected.Sat)
        vendored(
            "sporttournament06",
            Category.OPTIMIZATION,
            Expected.Opt(-6L),
            license = "PB Competition (academic benchmarks)",
        )
        vendored(
            "queens4-soft",
            Category.OPTIMIZATION,
            Expected.Opt(0L),
            relPath = "opb/queens4-soft.wbo",
            license = "PB Competition (academic benchmarks)",
        )
    }

    private val schemaCore = suite("schema-core", "klause JSON schema instances") {
        format = Format.JSON_SCHEMA
        license = "internal"
        vendored("campaign", Category.ASSIGNMENT, Expected.Sat)
        vendored("roster", Category.ASSIGNMENT, Expected.Sat)
    }

    private val smtlibCore = suite("smtlib-core", "Curated SMT-LIB QF_LIA instances") {
        format = Format.SMTLIB_QF_LIA
        license = "internal"
        vendored("lia-basic", Category.CSP, Expected.Sat)
        vendored("lia-opt", Category.OPTIMIZATION, Expected.Opt(7))
        vendored("lia-unsat", Category.UNSAT, Expected.Unsat)
        vendored("lia-disjunction", Category.CSP, Expected.Sat)
    }

    /** XCSP3 family key: the series prefix before the first `-`. Competition instance names encode
     *  the series then a `-`-joined parameter tail (`AircraftAssemblyLine-1-178-00-0_c23`), so this
     *  groups the ~76 COP / CSP series for `per-family` sampling instead of treating every
     *  parameterization as its own singleton family. */
    private fun xcspSeries(name: String): String = name.substringBefore('-')

    /** MaxSAT family key: the descriptive prefix before the first `-` of a flat MSE filename
     *  (`causal-discovery-causal_n6_…` → `causal`), grouping parameterizations for `per-family`
     *  sampling instead of treating every instance as its own family. */
    private fun maxsatFamily(name: String): String = name.substringBefore('-')

    private val xcsp3Core = suite("xcsp3-core", "Curated XCSP3 integer CSP/COP instances") {
        format = Format.XCSP3
        license = "internal"
        vendored("magic-series-tiny", Category.CSP, Expected.Sat, relPath = "xcsp3/magic-series-tiny.xml")
        vendored("sum-opt-tiny", Category.OPTIMIZATION, Expected.Unknown, relPath = "xcsp3/sum-opt-tiny.xml")
        vendored("magic-square-3", Category.CSP, Expected.Sat, relPath = "xcsp3/magic-square-3.xml")
        vendored("graph-coloring-tiny", Category.CSP, Expected.Sat, relPath = "xcsp3/graph-coloring-tiny.xml")
    }

    private val mpsCore = suite("mps-core", "Curated MPS (MIP) integer + bounded-float instances") {
        format = Format.MPS
        license = "internal"
        vendored("blend-tiny", Category.OPTIMIZATION, Expected.Opt(9))
        vendored("feasible-tiny", Category.CSP, Expected.Sat)
        vendored("float-tiny", Category.CSP, Expected.Sat)
        vendored("infeasible-tiny", Category.UNSAT, Expected.Unsat)
    }

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

    // --- SAT performance datasets ---

    /** SATLIB random-3SAT phase-transition ladder (uf=SAT, uuf=UNSAT), V=50…250 — labelled
     *  instances at increasing size for measuring CDCL scaling. A small sample per family
     *  (the full tarball is fetched once and cached). */
    private val satLadder = suite(
        "sat-ladder",
        "SATLIB random-3SAT phase-transition ladder (uf=SAT / uuf=UNSAT, 50–250 vars)",
    ) {
        format = Format.DIMACS
        license = "SATLIB (public benchmarks)"
        for ((name, col) in ExternalCollections.satlibLadder) {
            val sat = name.startsWith("uf")
            // The SATLIB tarballs sample instances with inconsistent zero-padding
            // (uf50-031.cnf, uf50-0433.cnf, …), so a guessed filename like uf50-01.cnf doesn't
            // exist. Reference the first few by index over the sorted collection instead.
            for (n in 0 until 5) {
                externalIndexed(
                    "$name-${n + 1}",
                    col,
                    index = n,
                    ext = "cnf",
                    category = if (sat) Category.SAT else Category.UNSAT,
                    expected = if (sat) Expected.Sat else Expected.Unsat,
                )
            }
        }
    }

    /** In-code crafted SAT: pigeonhole PHPₙ (UNSAT, CDCL stress) + random-3SAT at the phase
     *  transition. No fetch / no license; parametric for performance regression tracking. */
    private val satCrafted = suite(
        "sat-crafted",
        "In-code crafted SAT: pigeonhole (UNSAT) + random-3SAT (phase transition)",
    ) {
        for (n in 5..9) inCode("php$n", Category.UNSAT, Expected.Unsat) { SatGenerators.php(n) }
        for (v in intArrayOf(
            50,
            100,
            150,
            200,
            250,
        )) {
            inCode("rand3sat-$v", Category.SAT, Expected.Unknown) { SatGenerators.random3Sat(v) }
        }
    }

    private val mznSmoke = suite("mzn-smoke", "Mandatory MiniZinc smoke models (CI parity)") {
        format = Format.MINIZINC
        license = "internal"
        val base = "klause-mzn-lib/test-models"
        workspace("argmax", "$base/argmax.mzn", Category.CSP, Expected.Sat)
        workspace("bin_packing", "$base/bin_packing.mzn", Category.PACKING, Expected.Sat)
        workspace("connected", "$base/connected.mzn", Category.CSP, Expected.Sat)
        workspace("diffn_nonstrict_k", "$base/diffn_nonstrict_k.mzn", Category.PACKING, Expected.Sat)
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
 */
internal object ExternalCollections {
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
        url = "https://github.com/rasros/hakank.git",
        license = "MIT (Hakan Kjellerstrand)",
        reason = "the minizinc/ models from hakank/hakank; upstream is no longer maintained " +
            "following its author's passing, so fixes for models that no longer flatten under " +
            "current MiniZinc live in this derived copy",
        fetch = FetchMethod.GitClone(depth = 1),
        includeDirs = listOf("minizinc/lib"),
    )
    val miplib2017 = ExternalCollection(
        id = "miplib2017",
        url = "https://miplib.zib.de/downloads/collection.zip",
        license = "MIPLIB 2017 (academic; freely available for research)",
        reason = "1065-instance MIP library; the full collection.zip (~3.5GB) is fetched once, then " +
            "filtered to the <=16MB instances (SCIP references all, klause solves the bounded/integer subset)",
        fetch = FetchMethod.Zip,
        maxFileMb = 16,
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

    // --- XCSP3 competition library (instance archives, xcsp.org / CRIL) ---
    // Instances ship as individually `.xml.lzma`-compressed files inside each zip; the
    // coverage tool decompresses them on the fly. Per-year full archives cover 2017–2019 (no
    // competition in 2020–2021); the 2022–2025 range is taken from the two curated main-track
    // aggregates published at xcsp.org/instances (COP22to25, CSP22to25) rather than the
    // overlapping per-year archives — same range, deduplicated to the CSP/COP tracks klause targets.
    private fun xcsp(year: Int, mb: Int) = ExternalCollection(
        id = "xcsp3-$year",
        url = "https://www.cril.univ-artois.fr/~lecoutre/compets/instancesXCSP${year % 100}.zip",
        license = "XCSP3 competition (academic benchmarks)",
        reason = "${mb}MB competition archive; fetched rather than vendored",
        fetch = FetchMethod.Zip,
    )

    /** A curated main-track aggregate spanning the 2022–2025 competitions, published at
     *  xcsp.org/instances: `COP22to25` (1000 COP instances) and `CSP22to25` (800 CSP). */
    private fun xcspAggregate(track: String, count: Int) = ExternalCollection(
        id = "xcsp3-${track.lowercase()}-22to25",
        url = "https://www.cril.univ-artois.fr/~lecoutre/compets/${track}22to25.zip",
        license = "XCSP3 competition (academic benchmarks)",
        reason = "$count main-track $track instances (2022–2025 aggregate); fetched rather than vendored",
        fetch = FetchMethod.Zip,
    )

    /** The curated 2022–2025 main-track aggregates: the [xcsp3-cop]/[xcsp3-csp] dynamic suites select
     *  from these (each ships as `COP22to25/`/`CSP22to25/` dirs of `*.xml.lzma`, decompressed on fetch). */
    val xcsp3Cop = xcspAggregate("COP", 1000)
    val xcsp3Csp = xcspAggregate("CSP", 800)
    val xcsp3Competition = listOf(
        xcsp(2017, 102),
        xcsp(2018, 69),
        xcsp(2019, 136),
        xcsp3Cop,
        xcsp3Csp,
    )

    /** SMT-LIB QF_LIA non-incremental benchmark set: the per-logic `.tar.zst` archive from the
     *  SMT-LIB 2024 Zenodo release (record 11061097), extracting to `.smt2` files under
     *  `non-incremental/QF_LIA/<family>/`. */
    val smtlibQfLia = ExternalCollection(
        id = "smtlib-qf_lia",
        url = "https://zenodo.org/records/11061097/files/QF_LIA.tar.zst?download=1",
        license = "SMT-LIB (per-family licenses)",
        reason = "large benchmark set (689MB compressed, ~13k instances); fetched rather than vendored",
        fetch = FetchMethod.TarballZst,
    )

    /** SMT-LIB QF_LRA non-incremental benchmark set, same Zenodo release as [smtlibQfLia]. */
    val smtlibQfLra = ExternalCollection(
        id = "smtlib-qf_lra",
        url = "https://zenodo.org/records/11061097/files/QF_LRA.tar.zst?download=1",
        license = "SMT-LIB (per-family licenses)",
        reason = "real-arithmetic benchmark set (174MB compressed, ~1.7k instances); fetched rather than vendored",
        fetch = FetchMethod.TarballZst,
    )

    /** SMT-LIB QF_LIRA non-incremental benchmark set, same Zenodo release as [smtlibQfLia]. */
    val smtlibQfLira = ExternalCollection(
        id = "smtlib-qf_lira",
        url = "https://zenodo.org/records/11061097/files/QF_LIRA.tar.zst?download=1",
        license = "SMT-LIB (per-family licenses)",
        reason = "small mixed int/real benchmark set (0.2MB compressed); fetched rather than vendored",
        fetch = FetchMethod.TarballZst,
    )

    /** SATLIB random-3SAT phase-transition ladder: `uf<V>-<C>` (SAT) and `uuf<V>-<C>` (UNSAT),
     *  V = 50…250. Each is a 1000-instance family fetched on demand; flat `*.cnf` inside. */
    private fun satlibRnd(name: String) = ExternalCollection(
        id = "satlib-$name",
        url = "https://www.cs.ubc.ca/~hoos/SATLIB/Benchmarks/SAT/RND3SAT/$name.tar.gz",
        license = "SATLIB (public benchmarks)",
        reason = "1000-instance RND3SAT family; fetched rather than vendored",
        fetch = FetchMethod.Tarball,
    )

    /** SATLIB "classic" structured DIMACS families — small, hand-picked benchmarks each with a
     *  distinct structure (artificial random-3SAT, variable-length random, DUBOIS xor-chains, parity
     *  learning, inductive inference, pigeonhole, all-interval series). Each ships as a flat `*.cnf`
     *  tarball; the `dimacs-classic` suite selects them all as a diverse, klause-scale Boolean set. */
    private fun satlibClassic(id: String, path: String) = ExternalCollection(
        id = "satlib-$id",
        url = "https://www.cs.ubc.ca/~hoos/SATLIB/Benchmarks/SAT/$path",
        license = "SATLIB (public benchmarks)",
        reason = "small classic structured DIMACS family; fetched rather than vendored",
        fetch = FetchMethod.Tarball,
    )

    /** The classic families paired with a coarse SAT/UNSAT label (DUBOIS and pigeonhole are wholly
     *  UNSAT, the rest predominantly SAT); the clasp oracle decides each instance's true verdict, so
     *  the label is only the suite's category metadata. */
    val dimacsClassic: List<Pair<ExternalCollection, Category>> = listOf(
        satlibClassic("aim", "DIMACS/AIM/aim.tar.gz") to Category.SAT,
        satlibClassic("jnh", "DIMACS/JNH/jnh.tar.gz") to Category.SAT,
        satlibClassic("dubois", "DIMACS/DUBOIS/dubois.tar.gz") to Category.UNSAT,
        satlibClassic("parity", "DIMACS/PARITY/parity.tar.gz") to Category.SAT,
        satlibClassic("ii", "DIMACS/II/inductive-inference.tar.gz") to Category.SAT,
        satlibClassic("phole", "DIMACS/PHOLE/pigeon-hole.tar.gz") to Category.UNSAT,
        satlibClassic("ais", "AIS/ais.tar.gz") to Category.SAT,
    )
    val pbComp2024 = ExternalCollection(
        id = "pb-comp-2024",
        url = "https://www.cril.univ-artois.fr/PB24/benchs/selected-PB24.tar",
        license = "Pseudo-Boolean Competition (academic benchmarks)",
        reason = "255MB PB'24 selected-benchmark set; fetched rather than vendored",
        fetch = FetchMethod.Tar,
    )

    /** PB'07 evaluation "other" benchmarks: 487 native (crafted, not MIP-translated) pseudo-Boolean
     *  instances across the competition categories (optimization/decision, small/big integer, linear
     *  and non-linear), each a `.opb.bz2` under `normalized-PB07/`. Standard OPB headers, klause-scale.
     *  (The archive lists 598, but 111 long factor-mod paths exceed the old tar 100-char name limit.) */
    val pb07Other = ExternalCollection(
        id = "pb07-other",
        url = "http://www.cril.univ-artois.fr/PB07/benchs/PB07-OTHER.tar",
        license = "PB Evaluation (academic benchmarks)",
        reason = "31MB PB'07 native pseudo-Boolean set (487 crafted instances); fetched rather than vendored",
        fetch = FetchMethod.Tar,
    )
    val maxsatExactUnweighted = ExternalCollection(
        id = "mse24-exact-unweighted",
        url = "https://www.cs.helsinki.fi/group/coreo/MSE2024-instances/mse24-exact-unweighted.zip",
        license = "MaxSAT Evaluation (academic benchmarks)",
        reason = "1.9GB MSE'24 exact unweighted track (flat `*.wcnf.xz`); fetched rather than vendored",
        fetch = FetchMethod.Zip,
    )
    val maxsatExactWeighted = ExternalCollection(
        id = "mse24-exact-weighted",
        url = "https://www.cs.helsinki.fi/group/coreo/MSE2024-instances/mse24-exact-weighted.zip",
        license = "MaxSAT Evaluation (academic benchmarks)",
        reason = "4.4GB MSE'24 exact weighted track (flat `*.wcnf.xz`); fetched rather than vendored",
        fetch = FetchMethod.Zip,
    )

    /** Ordered rungs (low→high vars) of the SAT and UNSAT ladders, keyed by family name. */
    val satlibLadder: Map<String, ExternalCollection> = listOf(
        "uf50-218", "uf75-325", "uf100-430", "uf125-538", "uf150-645",
        "uf175-753", "uf200-860", "uf225-960", "uf250-1065",
        "uuf50-218", "uuf75-325", "uuf100-430", "uuf125-538", "uuf150-645",
        "uuf175-753", "uuf200-860", "uuf225-960", "uuf250-1065",
    ).associateWith { satlibRnd(it) }
}
