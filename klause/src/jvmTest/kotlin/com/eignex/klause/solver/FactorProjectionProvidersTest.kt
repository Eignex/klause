package com.eignex.klause.solver

import kotlin.test.Test
import kotlin.test.assertSame

class FactorProjectionProvidersTest {
    @Test
    fun `no argument JVM construction selects the built in providers`() {
        val providers = FactorProjectionProviders::class.java.getConstructor().newInstance()

        assertSame(FactorProjectionProviders.Default.propagation, providers.propagation)
        assertSame(FactorProjectionProviders.Default.scoring, providers.scoring)
        assertSame(FactorProjectionProviders.Default.relaxation, providers.relaxation)
        assertSame(FactorProjectionProviders.Default.encoder, providers.encoder)
    }
}
