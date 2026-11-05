package com.eignex.klause.util

import com.eignex.util.numbers.shl

internal class MutableIntObjectIndex<V : Any> {
    internal interface Slot<V : Any> {
        val key: Int
        val value: V
    }

    private sealed class Node<V : Any>(var parent: Branch<V>? = null)
    private class Leaf<V : Any>(
        override val key: Int,
        override var value: V,
        val owner: MutableIntObjectIndex<V>,
    ) : Node<V>(), Slot<V>
    private class Branch<V : Any>(
        val mask: Int,
        val prefix: Int,
        var zero: Node<V>,
        var one: Node<V>,
    ) : Node<V>()

    private var root: Node<V>? = null
    var size = 0
        private set
    var lastWork = 0L
        private set
    val primitiveCapacityBytes: Long get() = 12L + size * 4L + (size - 1).coerceAtLeast(0) * 8L
    val referenceCapacity: Long get() = 1L + size * 3L + (size - 1).coerceAtLeast(0) * 3L

    operator fun get(key: Int): V? {
        lastWork = 0L
        return closest(key)?.takeIf { it.key == key }?.value
    }

    fun lookup(key: Int, visit: (Long) -> Unit): V? {
        lastWork = 0L
        return closest(key, visit)?.takeIf { it.key == key }?.value
    }

    fun put(key: Int, value: V): Long {
        require(key >= 0)
        lastWork = 0L
        val found = closest(key)
        if (found?.key == key) {
            found.value = value
            lastWork++
        } else insert(key, value, found)
        return lastWork
    }

    fun getOrPut(key: Int, default: () -> V): V = getOrPutSlot(key, default).value

    fun getOrPutSlot(key: Int, default: () -> V): Slot<V> {
        require(key >= 0)
        lastWork = 0L
        val found = closest(key)
        if (found?.key == key) return found
        return insert(key, default(), found)
    }

    private fun insert(key: Int, value: V, representative: Leaf<V>?): Leaf<V> {
        val leaf = Leaf(key, value, this)
        if (representative == null) {
            root = leaf
            size++
            lastWork += 8L
            return leaf
        }
        val mask = 1 shl (31 - (key xor representative.key).countLeadingZeroBits())
        var node: Node<V> = representative
        var parent = node.parent
        while (parent != null && parent.mask < mask) {
            lastWork++
            node = parent
            parent = parent.parent
        }
        val prefix = key and (mask or (mask - 1)).inv()
        val branch = if (key and mask == 0) Branch(mask, prefix, leaf, node)
            else Branch(mask, prefix, node, leaf)
        branch.parent = parent
        node.parent = branch
        leaf.parent = branch
        replace(parent, node, branch)
        size++
        lastWork += 16L
        return leaf
    }

    fun firstAtOrAfter(key: Long, visit: (Long) -> Unit): Slot<V>? {
        if (key > Int.MAX_VALUE || key < 0L) return null
        return ceiling(root, key.toInt(), visit)
    }

    fun remove(slot: Slot<V>, fund: (Long) -> Unit, ready: () -> Unit): Boolean {
        val leaf = slot as? Leaf<V> ?: return false
        if (leaf.owner !== this || (leaf.parent == null && root !== leaf)) return false
        fund(12L)
        ready()
        val parent = leaf.parent
        if (parent == null) root = null
        else {
            val sibling = if (parent.zero === leaf) parent.one else parent.zero
            val grandparent = parent.parent
            replace(grandparent, parent, sibling)
            sibling.parent = grandparent
            parent.parent = null
        }
        leaf.parent = null
        size--
        lastWork = 12L
        return true
    }

    fun forEach(visit: (Long) -> Unit = {}, action: (Int, V) -> Unit) {
        walk(root, visit, action)
    }

    fun protectedCapacity(visit: (Long) -> Unit, protected: (Int, V) -> Boolean): Pair<Long, Long> {
        var bytes = 0L
        var references = 0L
        fun covered(node: Node<V>?): Boolean {
            if (node == null) return false
            visit(1L)
            val retained = when (node) {
                is Leaf -> protected(node.key, node.value)
                is Branch -> {
                    val zero = covered(node.zero)
                    val one = covered(node.one)
                    zero || one
                }
            }
            if (retained) {
                bytes += if (node is Leaf) 4L else 8L
                references += 3L
            }
            return retained
        }
        covered(root)
        return bytes to references
    }

    private fun closest(key: Int, visit: ((Long) -> Unit)? = null): Leaf<V>? {
        if (key < 0) return null
        var node = root
        while (node != null) {
            visit?.invoke(1L)
            lastWork++
            when (node) {
                is Leaf -> return node
                is Branch -> node = child(node, key)
            }
        }
        return null
    }

    private fun replace(parent: Branch<V>?, previous: Node<V>, next: Node<V>) {
        when {
            parent == null -> root = next
            parent.zero === previous -> parent.zero = next
            else -> { check(parent.one === previous); parent.one = next }
        }
    }

    private fun child(branch: Branch<V>, key: Int): Node<V> =
        if (key and branch.mask == 0) branch.zero else branch.one

    private fun minimum(start: Node<V>, visit: (Long) -> Unit): Leaf<V> {
        var node = start
        while (node is Branch) {
            visit(1L)
            node = node.zero
        }
        visit(1L)
        return node as Leaf<V>
    }

    private fun ceiling(node: Node<V>?, key: Int, visit: (Long) -> Unit): Leaf<V>? {
        if (node == null) return null
        visit(1L)
        return when (node) {
            is Leaf -> node.takeIf { it.key >= key }
            is Branch -> when {
                key > (node.prefix or node.mask or (node.mask - 1)) -> null
                key <= node.prefix -> minimum(node, visit)
                key and node.mask != 0 -> ceiling(node.one, key, visit)
                else -> ceiling(node.zero, key, visit) ?: minimum(node.one, visit)
            }
        }
    }

    private fun walk(node: Node<V>?, visit: (Long) -> Unit, action: (Int, V) -> Unit) {
        if (node == null) return
        visit(1L)
        when (node) {
            is Leaf -> action(node.key, node.value)
            is Branch -> { walk(node.zero, visit, action); walk(node.one, visit, action) }
        }
    }
}
