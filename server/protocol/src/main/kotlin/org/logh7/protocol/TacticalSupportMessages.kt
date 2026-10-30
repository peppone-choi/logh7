package org.logh7.protocol

import java.nio.ByteBuffer

/** Native 004a0d90, 004a7670 and 0043e9e0. Author: Peppone Choi. */
object TacticalSupportMessages {
    enum class Action(val opcode: Int) { REPAIR(0x0413), SUPPLY(0x0414) }

    /** Source is one support unit, not a counted list as in AttackShip. */
    data class Command(val action: Action, val character: Int, val unit: Int, val target: Int) {
        init { require(character > 0 && unit > 0 && target > 0) }
    }

    fun command(message: ByteArray): Command {
        require(message.size == 22)
        val input = ByteBuffer.wrap(message)
        val opcode = input.short.toInt() and 65535
        val action = requireNotNull(Action.entries.singleOrNull { it.opcode == opcode })
        input.int; input.int // Native time/wait are untrusted; the world actor chooses both.
        return Command(action, input.int, input.int, input.int)
    }

    /** Permission, support hull type, range and supplies must be resolved by the world actor. */
    fun reply(command: Command, seconds: Long, waitSeconds: Long, accepted: Boolean = true): ByteArray {
        require(seconds in 0..0xffffffffL && waitSeconds in 0..0xffffffffL - seconds)
        val body = ByteBuffer.allocate(20).putInt(seconds.toInt()).putInt(waitSeconds.toInt())
            .putInt(command.character).putInt(command.unit).putInt(command.target).array()
        return LoginMessages.sessionMessage(command.action.opcode, body).also {
            if (!accepted) ByteBuffer.wrap(it).putInt(0, 1)
        }
    }

    /** Absolute supplies and unavailable boats (destroyed + still damaged), not repairs performed. */
    fun repaired(unit: Int, target: Int, supplies: Int, damaged: Int): ByteArray {
        require(unit > 0 && target > 0 && supplies >= 0 && damaged in 0..65535)
        return LoginMessages.sessionMessage(0x042d, ByteBuffer.allocate(14)
            .putInt(unit).putInt(target).putInt(supplies).putShort(damaged.toShort()).array())
    }

    /** Both balances are absolute; transfer quantities alone cannot restore the native display. */
    fun supplied(unit: Int, target: Int, supplies: Int, targetSupplies: Int): ByteArray {
        require(unit > 0 && target > 0 && supplies >= 0 && targetSupplies >= 0)
        return LoginMessages.sessionMessage(0x042e, ByteBuffer.allocate(16)
            .putInt(unit).putInt(target).putInt(supplies).putInt(targetSupplies).array())
    }
}
