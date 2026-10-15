package com.eignex.klause.solver.pipeline

import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertNotNull

class ExecutionOptionsStartupTest {
    @Test
    fun `typed settings initialize before the frontend catalog`() {
        val parent = ExecutionOption::class.java.classLoader
        val location = ExecutionOption::class.java.protectionDomain.codeSource.location
        val name = ExecutionOption::class.java.name
        val loader = object : URLClassLoader(arrayOf(location), parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith("com.eignex.klause.solver.pipeline.ExecutionOption")) {
                    return super.loadClass(name, resolve)
                }
                return synchronized(getClassLoadingLock(name)) {
                    val loaded = findLoadedClass(name) ?: findClass(name)
                    if (resolve) resolveClass(loaded)
                    loaded
                }
            }
        }

        val seed = loader.use { Class.forName("$name\$Seed", true, it).getField("INSTANCE").get(null) }

        assertNotNull(seed)
    }
}
