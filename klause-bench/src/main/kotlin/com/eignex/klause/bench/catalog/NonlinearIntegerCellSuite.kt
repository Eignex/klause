package com.eignex.klause.bench.catalog

internal object NonlinearIntegerCellSuite {
    val suite = suite("smtlib-nonlinear-integer-cells-v1", "Guarded univariate cells in integer completion") {
        format = Format.SMTLIB
        license = "Apache-2.0 (self-authored Eignex Klause fixtures)"
        val cases = listOf(
            "powers/square-1" to Expected.Unsat,
            "powers/square-2" to Expected.Unsat,
            "powers/square-4" to Expected.Unsat,
            "powers/square-8" to Expected.Unsat,
            "degree/power-4" to Expected.Unsat,
            "degree/power-8" to Expected.Unsat,
            "degree/power-16" to Expected.Unsat,
            "degree/power-17" to Expected.Unsat,
            "cubic/root-neighbor" to Expected.Sat,
            "repeated/root-neighbor" to Expected.Sat,
            "rational/positive-empty" to Expected.Unsat,
            "rational/negative-empty" to Expected.Unsat,
            "annulus/strict-empty" to Expected.Unsat,
            "annulus/closed-neighbor" to Expected.Sat,
            "boolean/conditional-neighbor" to Expected.Sat,
            "mixed/singular-empty" to Expected.Unsat,
            "wide/integer-neighbor" to Expected.Sat,
        )
        for ((name, expected) in cases) {
            vendored(
                name,
                expected = expected,
                relPath = "nonlinear-integer-cells-v1/$name.smt2",
                tags = setOf("nonlinear-integer", "nonlinear-integer-cells-v1"),
            )
        }
    }
}
