package com.eignex.klause.bench.profile

import com.eignex.klause.propagation.PropagationSession

internal object LivePropagationState {
    private val retainedState = PropagationSession::class.java.getDeclaredField("retainedState")
        .apply { isAccessible = true }

    fun current(session: PropagationSession): Any? = retainedState.get(session)

    fun require(session: PropagationSession): Any = checkNotNull(current(session)) {
        "propagation session is released"
    }
}
