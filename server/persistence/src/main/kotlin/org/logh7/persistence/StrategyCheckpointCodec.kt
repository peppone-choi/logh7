package org.logh7.persistence

import org.logh7.engine.strategy.*
import java.io.*
import java.util.Base64

/** Bounded NAV-5 column; balances, pending work and resulting effects share one atomic file. */
internal object StrategyCheckpointCodec {
    fun encode(state: StrategyCheckpoint): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeLong(state.nextId); out.writeLong(state.lastGameMillis); out.writeInt(state.jobs.size)
            state.jobs.forEach { job ->
                out.writeLong(job.id); out.writeUTF(job.command)
                out.writeLong(job.acceptedAtGameMillis); out.writeLong(job.startsAtGameMillis); out.writeLong(job.completesAtGameMillis)
                out.writeLong(job.payment.balance.pcp); out.writeLong(job.payment.balance.mcp)
                out.writeUTF(job.payment.chargedPool?.name ?: "-")
                out.writeLong(job.payment.spent); out.writeLong(job.payment.experienceEligibleCp)
                out.writeBoolean(job.payment.substituted); out.writeUTF(job.state.name)
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
    fun decode(encoded: String): StrategyCheckpoint {
        require(encoded.length <= 200_000) { "Strategy checkpoint is too large" }
        return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            val nextId = input.readLong(); val last = input.readLong(); val count = input.readInt()
            require(count in 0..128)
            val jobs = List(count) {
                val id = input.readLong(); val command = input.readUTF()
                val accepted = input.readLong(); val starts = input.readLong(); val completes = input.readLong()
                val balance = CpBalance(input.readLong(), input.readLong())
                val pool = input.readUTF().let { if (it == "-") null else CpPool.valueOf(it) }
                val spent = input.readLong(); val experience = input.readLong(); val substituted = input.readBoolean()
                StrategyJob(id, command, accepted, starts, completes,
                    PaymentResult.Paid(balance, pool, spent, experience, substituted), JobState.valueOf(input.readUTF()))
            }
            require(input.read() == -1) { "Trailing strategy checkpoint data" }
            StrategyCheckpoint(nextId, last, jobs)
        }
    }
}
