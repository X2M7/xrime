/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.provider

import java.io.File
import java.io.FileNotFoundException

/** Complete bounded preflight; callers must not mutate a partial traversal. */
internal object DocumentsTreeBudget {
    const val MAX_NODES = 10_000
    const val MAX_DEPTH = 32
    const val MAX_COPY_BYTES = 512L * 1024 * 1024
    data class Node(val file: File, val directory: Boolean, val size: Long)
    private data class Pending(val file: File, val depth: Int)

    fun snapshot(
        root: File,
        maxNodes: Int = MAX_NODES,
        maxDepth: Int = MAX_DEPTH,
        maxBytes: Long = Long.MAX_VALUE,
        allowed: (File) -> Boolean,
        children: (File, Int) -> List<File>,
    ): List<Node> {
        require(maxNodes > 0 && maxDepth >= 0 && maxBytes >= 0)
        val pending = java.util.ArrayDeque<Pending>()
        val visited = HashSet<String>()
        val result = ArrayList<Node>()
        var totalBytes = 0L
        pending.addLast(Pending(root, 0))
        while (pending.isNotEmpty()) {
            val (file, depth) = pending.removeLast()
            if (depth > maxDepth || result.size >= maxNodes) throw FileNotFoundException("Document tree exceeds its traversal budget")
            if (!allowed(file) || !file.exists()) throw FileNotFoundException("Unsafe or missing document")
            val canonical = try { file.canonicalPath } catch (_: java.io.IOException) { throw FileNotFoundException("Cannot resolve document") }
            if (!visited.add(canonical)) throw FileNotFoundException("Recursive document tree")
            val directory = file.isDirectory
            if (!directory && !file.isFile) throw FileNotFoundException("Unsupported document type")
            if (!directory && maxBytes != Long.MAX_VALUE && !file.canRead()) throw FileNotFoundException("Cannot read document")
            val size = if (directory) 0L else file.length()
            if (size < 0 || size > maxBytes - totalBytes) throw FileNotFoundException("Document copy exceeds its byte budget")
            totalBytes += size
            result.add(Node(file, directory, size))
            if (directory) {
                val remaining = maxNodes - result.size - pending.size
                val descendants = children(file, remaining)
                if (descendants.size > remaining) throw FileNotFoundException("Document tree exceeds its traversal budget")
                if (descendants.isNotEmpty() && depth >= maxDepth) throw FileNotFoundException("Document tree exceeds its depth budget")
                descendants.forEach { pending.addLast(Pending(it, depth + 1)) }
            }
        }
        return result
    }
}
