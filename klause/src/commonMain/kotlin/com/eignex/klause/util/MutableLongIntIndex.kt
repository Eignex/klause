package com.eignex.klause.util

internal class MutableLongIntIndex {
    private class Node(var key: Long, var value: Int, var left: Node? = null,
        var right: Node? = null, var height: Int = 1)

    private var root: Node? = null
    var size: Int = 0
        private set
    var lastMutationWork = 0L
        private set
    val primitiveCapacityBytes: Long get() = 12L + size * 16L
    val referenceCapacity: Long get() = 1L + size * 2L
    val readWork: Long get() = height(root) + 1L
    // An Int-sized AVL tree has height below 46; the reserve covers its successor walk.
    val mutationWork: Long get() = 16L * (height(root) + 2L) + 32L

    fun getOrDefault(key: Long, default: Int): Int = find(key)?.value ?: default

    fun put(key: Long, value: Int): Long {
        lastMutationWork = 0L
        root = insert(root, key, value)
        return lastMutationWork
    }

    fun remove(key: Long): Boolean {
        lastMutationWork = 0L
        val before = size
        root = delete(root, key)
        return size < before
    }

    fun removeAndGet(key: Long, default: Int): Int {
        lastMutationWork = readWork
        val previous = find(key)?.value ?: return default
        root = delete(root, key)
        return previous
    }

    fun removeAndUpdate(
        key: Long,
        updateKey: Long?,
        updateValue: Int,
        fund: (Long) -> Unit,
        ready: () -> Unit,
    ): Boolean {
        require(key != updateKey)
        fund(readWork)
        val removed = find(key)
        val updated = updateKey?.let {
            fund(readWork)
            find(it) ?: return false
        }
        fund((if (removed == null) 0L else mutationWork) + 4L)
        ready()
        lastMutationWork = 4L
        // Updating before deletion preserves a successor's value if it is copied into the removed node.
        if (updated != null) updated.value = updateValue
        if (removed != null) root = delete(root, key)
        return true
    }

    fun clear() {
        lastMutationWork = 2L
        root = null
        size = 0
    }

    private fun find(key: Long): Node? {
        var node = root
        while (node != null) {
            node = when {
                key < node.key -> node.left
                key > node.key -> node.right
                else -> return node
            }
        }
        return null
    }

    private fun insert(node: Node?, key: Long, value: Int): Node {
        if (node == null) { lastMutationWork += 8L; size++; return Node(key, value) }
        lastMutationWork += 4L
        return when {
            key < node.key -> { node.left = insert(node.left, key, value); balance(node) }
            key > node.key -> { node.right = insert(node.right, key, value); balance(node) }
            else -> node.also { it.value = value }
        }
    }

    private fun delete(node: Node?, key: Long): Node? {
        if (node == null) { lastMutationWork++; return null }
        lastMutationWork += 4L
        when {
            key < node.key -> node.left = delete(node.left, key)
            key > node.key -> node.right = delete(node.right, key)
            else -> {
                if (node.left == null) { lastMutationWork += 4L; size--; return node.right }
                if (node.right == null) { lastMutationWork += 4L; size--; return node.left }
                var successor = checkNotNull(node.right)
                while (successor.left != null) {
                    lastMutationWork++
                    successor = checkNotNull(successor.left)
                }
                node.key = successor.key
                node.value = successor.value
                node.right = delete(node.right, successor.key)
            }
        }
        return balance(node)
    }

    private fun balance(node: Node): Node {
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

    private fun rotateLeft(node: Node): Node {
        lastMutationWork += 6L
        val next = checkNotNull(node.right)
        node.right = next.left
        next.left = node
        update(node)
        update(next)
        return next
    }

    private fun rotateRight(node: Node): Node {
        lastMutationWork += 6L
        val next = checkNotNull(node.left)
        node.left = next.right
        next.right = node
        update(node)
        update(next)
        return next
    }

    private fun height(node: Node?): Int = node?.height ?: 0
    private fun update(node: Node) { node.height = 1 + maxOf(height(node.left), height(node.right)) }
}
