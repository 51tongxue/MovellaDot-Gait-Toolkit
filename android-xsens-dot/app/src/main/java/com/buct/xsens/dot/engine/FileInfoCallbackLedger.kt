package com.buct.xsens.dot.engine

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

internal class FileInfoCallbackLedger(
    private val normalizeAddress: (String) -> String,
) {
    private val outstanding = ConcurrentHashMap<String, ArrayDeque<Int>>()

    fun hasOutstanding(address: String): Boolean {
        val queue = outstanding[normalizeAddress(address)] ?: return false
        return synchronized(queue) { queue.isNotEmpty() }
    }

    fun enqueue(
        address: String,
        requestId: Int,
    ) {
        val queue = outstanding.computeIfAbsent(
            normalizeAddress(address),
        ) { ArrayDeque() }
        synchronized(queue) {
            queue.addLast(requestId)
        }
    }

    fun pop(address: String): Int? {
        val normalized = normalizeAddress(address)
        val queue = outstanding[normalized] ?: return null
        return synchronized(queue) {
            val requestId = if (queue.isEmpty()) null else queue.removeFirst()
            if (queue.isEmpty()) outstanding.remove(normalized, queue)
            requestId
        }
    }

    fun remove(
        address: String,
        requestId: Int,
    ) {
        val normalized = normalizeAddress(address)
        val queue = outstanding[normalized] ?: return
        synchronized(queue) {
            queue.removeFirstOccurrence(requestId)
            if (queue.isEmpty()) outstanding.remove(normalized, queue)
        }
    }

    fun clear() {
        outstanding.clear()
    }
}
