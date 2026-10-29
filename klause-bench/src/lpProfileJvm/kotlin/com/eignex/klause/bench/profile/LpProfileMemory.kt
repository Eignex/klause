package com.eignex.klause.bench.profile

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory

internal actual fun profileAllocatedBytes(): Long? {
    val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return null
    if (!bean.isThreadAllocatedMemorySupported || !bean.isThreadAllocatedMemoryEnabled) return null
    return bean.getThreadAllocatedBytes(Thread.currentThread().threadId()).takeIf { it >= 0L }
}
