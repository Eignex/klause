package com.eignex.klause.bench.catalog

internal object ContinuousArithmeticSuite {
    val suite = suite("continuous-arithmetic", "Continuous product source and grid execution controls") {
        license = "internal"
        for ((name, optimum) in listOf("square" to 1, "product" to 0, "chain" to 0)) {
            vendoredMzn(name, Category.OPTIMIZATION, Expected.Opt(optimum),
                model = "continuous-arithmetic/$name.mzn")
        }
    }
}
