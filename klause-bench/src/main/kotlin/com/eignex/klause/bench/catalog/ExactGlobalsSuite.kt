package com.eignex.klause.bench.catalog

internal object ExactGlobalsSuite {
    val suite = suite("exact-globals", "Advertised MiniZinc global wrapper compositions") {
        license = "internal"
        for (name in listOf("bin-packing", "bin-packing-capa", "bin-packing-load", "count-variable",
            "circuit-offset", "subcircuit-offset", "symmetric-offset", "inverse-offset",
            "float-array-maximum", "float-array-minimum")) {
            for (coupling in 0..2) {
                for (claim in 0..2) {
                    vendoredMzn("$name-$coupling-$claim", Category.OPTIMIZATION,
                        if (claim == 1) Expected.Unsat else Expected.Opt(1),
                        model = "exact-globals/$name.mzn", data = "exact-globals/$coupling-$claim.dzn")
                }
            }
        }
    }
}
