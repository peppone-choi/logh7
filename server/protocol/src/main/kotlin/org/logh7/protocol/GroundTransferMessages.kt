package org.logh7.protocol

import java.nio.ByteBuffer
import java.util.Collections

/** 00449e20 / 0044a350: five u32 fields, u8 count, at most three u32 values.
 * The native field is load_unit/unload_unit. Keep its values opaque until the UI producer
 * is recovered; the formatter alone does not establish unit IDs versus quantities. Author: Peppone Choi.
 */
object GroundTransferMessages {
    enum class Action(val opcode:Int) { UNLOAD(0x0b04), LOAD(0x0b05) }
    class Command(val action:Action,val character:Int,val outfit:Int,values:List<Long>) {
        val values:List<Long> = Collections.unmodifiableList(ArrayList(values))
        init {
            require(character>0 && outfit>0 && values.size<=3 && values.all {it in 0..0xffffffffL})
        }
    }
    /** Proposed time and CP balances are deliberately discarded, as in other strategy codecs. */
    fun command(message:ByteArray):Command {
        require(message.size in 23..35)
        val input=ByteBuffer.wrap(message)
        val action=requireNotNull(Action.entries.singleOrNull {it.opcode==(input.getShort(0).toInt() and 65535)})
        input.short;input.int
        val character=input.int
        input.int;input.int
        val outfit=input.int
        val count=input.get().toInt() and 255
        require(count<=3 && input.remaining()==count*4)
        return Command(action,character,outfit,List(count) {input.int.toLong() and 0xffffffffL})
    }
    /** Extension marker 'LG' in the high half of the single value; the compat module reads the
     * low half as the result reason and clears the value before the native handler sees it. */
    const val REASON_MARK=0x4c470000L
    const val NO_CHANGE=0x40
    fun reply(command:Command,seconds:Long,pcp:Int,mcp:Int,accepted:Boolean=true,reason:Int=0):ByteArray {
        require(seconds in 0..0xffffffffL && pcp>=0 && mcp>=0 && reason in 0..0xffff)
        val values=if(reason!=0)listOf(REASON_MARK or reason.toLong()) else command.values
        val out=ByteBuffer.allocate(21+4*values.size)
            .putInt(seconds.toInt()).putInt(command.character).putInt(pcp).putInt(mcp).putInt(command.outfit)
            .put(values.size.toByte())
        values.forEach {out.putInt(it.toInt())}
        return LoginMessages.sessionMessage(command.action.opcode,out.array(),if(accepted)0 else 1)
    }
}
