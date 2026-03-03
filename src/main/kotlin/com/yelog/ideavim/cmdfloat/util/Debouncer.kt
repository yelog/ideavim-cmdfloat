package com.yelog.ideavim.cmdfloat.util

import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.atomic.AtomicReference

/**
 * A debouncer that delays execution of a task until a specified delay has passed
 * since the last call. Useful for rate-limiting expensive operations like search
 * preview updates during rapid user input.
 */
class Debouncer(private val delayMs: Long) {

    private val pendingTask = AtomicReference<Runnable?>(null)

    /**
     * Schedule a task to run after the delay. If another task is scheduled
     * before the delay expires, the previous task is cancelled.
     */
    fun debounce(task: () -> Unit) {
        // Create a wrapper that holds reference to itself for comparison
        val wrapper = object : Runnable {
            override fun run() {
                // Only execute if this is still the pending task
                if (pendingTask.compareAndSet(this, null)) {
                    task()
                }
            }
        }

        // Cancel any pending task by replacing it
        pendingTask.getAndSet(wrapper)

        // Schedule the new task
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                Thread.sleep(delayMs)
                if (pendingTask.get() === wrapper) {
                    ApplicationManager.getApplication().invokeLater {
                        wrapper.run()
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Cancel any pending debounced task.
     */
    fun cancel() {
        pendingTask.set(null)
    }

    /**
     * Execute a task immediately, cancelling any pending debounced task.
     */
    fun executeImmediately(task: () -> Unit) {
        cancel()
        task()
    }
}
