package org.logh7.engine

/** Only the generation marker is stored here; world snapshots/WAL belong to LOGH-43. */
interface SessionGenerationStore {
    fun current(): Long
    fun advance(expected: Long): Long
}
class MemorySessionGenerationStore(private var generation: Long = 1) : SessionGenerationStore {
    override fun current() = generation
    override fun advance(expected: Long): Long {
        check(expected == generation)
        generation = Math.addExact(generation, 1)
        return generation
    }
}
