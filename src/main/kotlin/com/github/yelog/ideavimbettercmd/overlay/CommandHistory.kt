package com.github.yelog.ideavimbettercmd.overlay

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CommandHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    private val lock = ReentrantLock()
    private val entries = ArrayList<String>(capacity)

    fun add(value: String) {
        if (value.trim().isEmpty()) {
            return
        }
        val normalized = value
        lock.withLock {
            entries.remove(normalized)
            entries.add(0, normalized)
            if (entries.size > capacity) {
                entries.removeLast()
            }
        }
    }

    fun snapshot(): List<String> = lock.withLock { entries.toList() }

    companion object {
        private const val DEFAULT_CAPACITY = 20
    }
}
