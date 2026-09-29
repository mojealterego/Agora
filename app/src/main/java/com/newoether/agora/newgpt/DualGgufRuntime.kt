package com.newoether.agora.newgpt

import com.newoether.agora.api.LlamaChatEngine
import java.io.File

/**
 * Explicit two-model llama.cpp workspace for NewGPT.
 * The existing LocalModelRuntime remains the canonical single-model chat path.
 */
class DualGgufRuntime : AutoCloseable {
    private val engines = arrayOfNulls<LlamaChatEngine>(2)

    @Synchronized
    fun loadBoth(firstPath: String, secondPath: String, contextSize: Int = 4096): Boolean {
        close()
        val paths = listOf(firstPath, secondPath)
        if (paths.any { !File(it).isFile || File(it).length() == 0L }) return false
        paths.forEachIndexed { index, path ->
            val engine = LlamaChatEngine(path, contextSize)
            if (engine.load()) engines[index] = engine else engine.close()
        }
        if (engines.any { it == null }) {
            close()
            return false
        }
        return true
    }

    fun isLoaded(slot: Int): Boolean = slot in 0..1 && engines[slot]?.isLoaded() == true

    fun modelPath(slot: Int): String? = if (slot in 0..1) engines[slot]?.modelPath else null

    fun engine(slot: Int): LlamaChatEngine? = if (slot in 0..1) engines[slot] else null

    @Synchronized
    override fun close() {
        engines.forEach { it?.close() }
        engines[0] = null
        engines[1] = null
    }
}
