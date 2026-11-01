package com.eignex.klause.solver.search

import com.eignex.klause.util.SharingMemoryObservation

internal class LearnedIndex<V : Any> {
    private class Node<V>(val key: Long, var value: V, var left: Node<V>? = null,
        var right: Node<V>? = null, var height: Int = 1)
    private var root: Node<V>? = null
    var size: Int = 0
        private set
    val readWork: Long get() = (height(root) + 1).toLong()
    val mutationWork: Long get() = 16L * (height(root) + 2L) + 16L
    val scratchBytes: Long get() = 48L * (height(root) + 1L)
    val ownedBytes: Long get() = size * 36L + 8L

    operator fun get(key: Long): V? {
        var node = root
        while (node != null) {
            node = when {
                key < node.key -> node.left
                key > node.key -> node.right
                else -> return node.value
            }
        }
        return null
    }

    fun next(after: Long, descending: Boolean = false): V? {
        var node = root
        var found: V? = null
        while (node != null) {
            if (if (descending) node.key < after else node.key > after) {
                found = node.value
                node = if (descending) node.right else node.left
            } else node = if (descending) node.left else node.right
        }
        return found
    }

    fun put(key: Long, value: V) {
        root = insert(root, key, value)
    }

    fun remove(key: Long) {
        root = delete(root, key)
    }

    fun forEach(action: (Long, V) -> Unit) {
        fun visit(node: Node<V>?) {
            if (node == null) return
            visit(node.left)
            action(node.key, node.value)
            visit(node.right)
        }
        visit(root)
    }

    fun memory(sample: SharingMemoryObservation, protected: (V) -> Boolean): Boolean {
        fun visit(node: Node<V>?): Boolean {
            if (node == null) return false
            sample.step()
            val left = visit(node.left)
            val right = visit(node.right)
            val retained = protected(node.value) || left || right
            sample.owned(node, 12L, retained)
            sample.ownedReferences(node, 3L, retained)
            return retained
        }
        val retained = visit(root)
        sample.ownedReferences(this, 1L, retained)
        return retained
    }

    private fun insert(node: Node<V>?, key: Long, value: V): Node<V> {
        if (node == null) { size++; return Node(key, value) }
        return when {
            key < node.key -> { node.left = insert(node.left, key, value); balance(node) }
            key > node.key -> { node.right = insert(node.right, key, value); balance(node) }
            else -> node.also { it.value = value }
        }
    }

    private fun delete(node: Node<V>?, key: Long): Node<V>? {
        node ?: return null
        when {
            key < node.key -> node.left = delete(node.left, key)
            key > node.key -> node.right = delete(node.right, key)
            else -> {
                if (node.left == null) { size--; return node.right }
                if (node.right == null) { size--; return node.left }
                var successor = checkNotNull(node.right)
                while (successor.left != null) successor = checkNotNull(successor.left)
                val replacement = Node(successor.key, successor.value, node.left, delete(node.right, successor.key))
                return balance(replacement)
            }
        }
        return balance(node)
    }

    private fun balance(node: Node<V>): Node<V> {
        update(node)
        if (height(node.left) - height(node.right) > 1) {
            if (height(node.left?.left) < height(node.left?.right)) node.left = rotateLeft(checkNotNull(node.left))
            return rotateRight(node)
        }
        if (height(node.right) - height(node.left) > 1) {
            if (height(node.right?.right) < height(node.right?.left)) node.right = rotateRight(checkNotNull(node.right))
            return rotateLeft(node)
        }
        return node
    }

    private fun rotateLeft(node: Node<V>): Node<V> {
        val next = checkNotNull(node.right)
        node.right = next.left
        next.left = node
        update(node)
        update(next)
        return next
    }

    private fun rotateRight(node: Node<V>): Node<V> {
        val next = checkNotNull(node.left)
        node.left = next.right
        next.right = node
        update(node)
        update(next)
        return next
    }

    private fun height(node: Node<V>?): Int = node?.height ?: 0
    private fun update(node: Node<V>) { node.height = 1 + maxOf(height(node.left), height(node.right)) }
}

internal class SharedReclamationBudget(
    val allowance: Long,
    private val scratchAllowance: Long,
    private val cancelled: () -> Boolean,
    private val charge: () -> Unit,
) {
    var work = 0L
        private set
    var scratch = 0L
        private set
    var blockedWork = 0L
        private set
    var blockedScratch = false
        private set

    fun pay(amount: Long, scratchBytes: Long = 64L) {
        if (cancelled()) throw Stopped(true)
        if (scratchBytes > scratchAllowance) {
            blockedScratch = true
            throw Stopped(false)
        }
        scratch = maxOf(scratch, scratchBytes)
        if (amount > allowance - work) {
            blockedWork = amount
            throw Stopped(false)
        }
        var remaining = amount
        while (remaining-- > 0L) {
            if (cancelled()) throw Stopped(true)
            charge()
            work++
        }
        if (cancelled()) throw Stopped(true)
    }

    val canResume: Boolean get() = !blockedScratch && blockedWork <= allowance
    class Stopped(val cancelled: Boolean) : RuntimeException()
}
