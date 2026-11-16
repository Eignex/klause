package com.eignex.klause.bench.catalog

internal object RewriteStructureSuite {
    val suite = suite("rewrite-structure-v1", "Affine Table and Element representation controls") {
        format = Format.XCSP3
        license = "Apache-2.0"
        for ((name, optimum) in listOf("affine-table" to 0L, "element-cone" to 2L)) {
            vendored(name, Category.OPTIMIZATION, Expected.Opt(optimum),
                relPath = "rewrite-structure-v1/$name.xml", tags = setOf("presolve-structure"))
        }
    }
    val canonicalSuite = suite("rewrite-structure-canonical-v1", "Canonical XCSP3 Element rewrite control") {
        format = Format.XCSP3
        license = "Apache-2.0"
        vendored("element-cone", Category.OPTIMIZATION, Expected.Opt(2L),
            relPath = "rewrite-structure-canonical-v1/element-cone.xml", tags = setOf("presolve-structure"))
    }
}
