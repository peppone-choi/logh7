package org.logh7.protocol

import java.nio.ByteBuffer

/** Explicit revival extension of the recovered 0055b3a0 simple-directory codec.
 * Search 0x7f00 is not an original server command. Its native factory is absent for
 * training/suppression/volunteering; the compatibility DLL provides the missing UI.
 * The envelope retains the original 31-byte shape and carries no trusted CP/time/rank.
 * Author: Peppone Choi. */
object StrategyActionMessages {
    const val SEARCH=0x7f00
    data class Query(val character:Int,val command:Int,val card:Int,val outfit:Int,val raw:ByteArray,
        val target:Int=0,val base:Int=0,val value:Int=0,val preview:Boolean=false,val amount:Int=0,val argument:Int=0) {
        val hasTradeQuote get()=command==40 || command==39 || command==20 && value==2
        val isTradeQuote get()=preview && hasTradeQuote && (if(command==40)amount>0 else argument==-1)
        val isProductionQuote get()=preview && command in 31..36 && argument==-1
        val spendsEvaluation get()=command==96 && (argument==0 || argument==4)
    }
    fun matches(message:ByteArray)=message.size>=8 && ByteBuffer.wrap(message).let {
        it.getShort(0).toInt()==0x1200 && (it.getShort(6).toInt() and 65535)==SEARCH }
    fun query(message:ByteArray):Query {
        require(matches(message) && message.size==31)
        val b=ByteBuffer.wrap(message)
        val character=b.getInt(2);val command=b.getShort(8).toInt() and 65535
        val card=b.getShort(10).toInt() and 65535;val target=b.getInt(14)
        val value=b.getShort(12).toInt() and 65535;val base=b.getInt(25)
        val preview=message[18]==1.toByte()
        val personnel=command in setOf(0,1,2,3,4,8,9)
        val operation=command in 22..24
        val market=command==96
        val civic=command in setOf(52,54,55,56)
        val transport=command in setOf(28,29,65)
        // 命令/提案 send (design-proposals-orders.md): card=own card, target=recipient, value=native command, argument=recipient card.
        val proposal=command==98
        // U-25 (design-arrest-judgment.md): 処断 19 value=verdict, 逮捕許可 69 value=charge, 執行命令 70 base=listed person
        // value=0 grant/1 revoke, 逮捕命令 71 (card 0 for a right holder). Previews: 70 value 0 lists the arrest list, 1 grantees.
        // Phase 2: 一斉捜索 68 and 襲撃 73 name only the card and the target.
        val justice=command in setOf(19,68,69,70,71,73,74,86)
        // U-17 工作 75..79: card = intelligence card, base = the spot to infiltrate (75); 75 previews list this base's spots.
        // 80 侵入 / 81 帰還: base = the destination base, value = the alias (80); previews list reachable bases.
        val espionage=command in 75..81
        // U-10 クーデター: 叛意 88, 謀議 89 (target = same-spot person; previews list them), 説得 90, 叛乱 91, 参加 92 on the
        // personal card; 査閲 72 on a police/intelligence card.
        val coup=command in setOf(72,88,89,90,91,92)
        // 資金投入 95: value = 0 local treasury / 1 confidence box / 2 support box, target = base / 1 for 2 against / candidate,
        // amount = money; previews (value = the mode) list the targets.
        val funds=command==95
        // 国家目標 15: target = an enemy base; 統治目標 21: target = a governed planet, value = focus 1..3. Previews list them.
        val goal=command in setOf(15,21)
        // Trade: 17 value = tariff %, 20 value = negotiation 0..2, 39 target = destination base (previews list them),
        // 40: amount >0 on a preview requests a quote, and commit argument is quoted net.
        // 39 and 20/value2: preview argument -1 quotes the selected target, commit argument is amount + 1.
        // All three retain argument 0 for old clients and directory previews.
        val trade=command in setOf(17,20,39,40)
        // U-08: value is the unit kind, preview argument -1 quotes, and commit base binds the quoted site.
        val production=command in 31..36
        // U-31 orbital ground attack from the compatibility DLL's commander-tab button: no card, no arguments.
        val strike=command==230
        // Imperial succession: 233 後継指名 on the emperor card (target = heir; previews list empire people), 234 継承拒否 on the personal card.
        val succession=command in setOf(233,234)
        val argument=b.getInt(19);val amount=b.getShort(23).toInt() and 65535
        require(character>0 && command in setOf(0,1,2,3,4,8,9,10,11,12,13,15,17,19,20,21,31,32,33,34,35,36,39,40,230,233,234,22,23,24,28,29,65,68,69,70,71,73,74,72,75,76,77,78,79,80,81,86,88,89,90,91,92,95,98,46,47,48,49,50,51,52,53,54,55,56,61,84,85,87,96) && card in 0..260 && target>=0 &&
            message[18] in byteArrayOf(0,1))
        require(!preview || personnel || command==24 || market || production || command in setOf(233,10,11,12,13,19,20,29,40,65,68,69,70,71,73,74,75,80,81,87,89,95,98,15,21,39))
        // 会見 87 uses this directory only to list people in the requester's spot; commits travel in 7f12.
        // 会談 12/談話 13 list same-base people of the same side; commits travel in 7f13.
        if(command in setOf(10,11,12,13,87))require(preview && card==0)
        if(!operation && !market && !transport && !proposal && !funds && !trade && !production)require(argument==0 && amount==0)
        if(civic)require(!preview && value in 0..2 && base==0 && argument==0 && amount==0 &&
            card in setOf(39,44,47,50,59,175,180,183,186,195) &&
            (if(command==55)target==0 && card in setOf(59,195) else value!=0 || target>0))
        else if(personnel)require((preview || target>0) && (command in setOf(3,4) || value==0) &&
            (if(command in setOf(8,9))base in 0..65535 else base==0))
        else if(operation)require((preview || target in 0..4999) && if(command==22)value in 0..2 && amount>0 && argument in 0..127 && base==0
            else value==0 && amount==0 && argument==0 && (if(command==24)!preview && base>0 || preview && base==0 else base==0))
        // Market arguments: 0 buy flagship, 1 offer, 2 accept, 3 cancel, 4 buy guard ship
        // (value = GuardShips index, 0..25), 5 disband the guard unit (U-13).
        else if(market)require(card==0 && base==0 && amount==0 && argument in 0..5 &&
            (if(argument==0)value in 0..3 && (preview || target==0) else if(argument==4)value in 0..25 && (preview || target==0)
             else if(argument==5)!preview && value==0 && target==0 else value==0 &&
                (if(argument in 1..2)preview || target>0 else !preview && target==0)))
        // card/argument 0 lets the server pick the same-institution pair; a preview lists the targets.
        // Dismissal (value 0x0708) also names the third person (base) and that person's card (amount).
        // Previews: value 0 lists recipients; value 0x0708 with base = the recipient lists the seats it may dismiss.
        // Appointment (0x0707): base = the seat and amount = the card; its preview lists the recipient's vacancies.
        else if(proposal)require(card in 0..260 && argument in 0..260 && amount in 0..260 && base>=0 &&
            (if(preview)card==0 && argument==0 && amount==0 && (value==0 && base==0 || value in setOf(0x0707,0x0708) && base>0)
             else target>0 && value in 1..0xffff && (if(value in setOf(0x0707,0x0708))base>0 && amount>0 else base==0 && amount==0)))
        else if(justice)require(card in 0..260 && argument==0 && amount==0 && (if(preview)command!=86 && value in 0..1 && (base==0 || command==70 && value==1 && base>0)
             else if(command==86)card==0 && target==0 && base==0 && value in setOf(0,2,3)
             else target>0 && when(command) {69 -> card>0 && value in 1..7 && base==0;70 -> card>0 && value in 0..1 && base>0
                 71 -> value==0 && base==0;68,73,74 -> card>0 && value==0 && base==0;else -> card>0 && value in 1..3 && base==0}))
        else if(espionage)require(card in 1..260 && argument==0 && amount==0 && target==0 &&
            (if(preview)command in setOf(75,80,81) && base==0 else if(command in setOf(75,80,81))base>0 else base==0) &&
            (value==0 || command==80 && !preview && value in 0..7))
        else if(succession)require(base==0 && argument==0 && amount==0 && value==0 &&
            (if(command==234)card==0 && target==0 && !preview else card==1 && (preview || target>0)))
        else if(strike)require(!preview && card==0 && base==0 && argument==0 && amount==0 && target==0 && value==0)
        else if(production)require(card in 1..260 && amount==0 && target==0 &&
            (value==0 || command in 35..36 && value in 0..199) &&
            if(preview)base==0 && (argument==-1 || argument==0 && command in 35..36 && value==0)
            else base in 0..65535 && argument==0)
        else if(trade)require(base==0 && when(command) {
            17 -> !preview && card in 1..260 && value in 0..50 && target==0 && amount==0 && argument==0
            20 -> card in 1..260 && amount==0 && (if(preview)value==2 else value in 0..2) &&
                (if(value==2)tradeQuoteArgument(preview,target,argument) else target==0 && argument==0)
            39 -> card==0 && value==0 && amount==0 && (preview || target>0) && tradeQuoteArgument(preview,target,argument)
            else -> card in 1..260 && value==0 && (if(preview)argument==0 && (amount==0 || target>0)
                else amount>0 && (argument==0 || argument>0 && target>0))})
        else if(goal)require(card in 1..260 && base==0 && argument==0 && amount==0 &&
            (if(preview)value==0 else target>0 && (if(command==15)value==0 else value in 1..3)))
        else if(funds)require(card==0 && base==0 && argument==0 && value in 0..2 && (if(preview)amount==0 else target>0 && amount>0))
        else if(coup)require(argument==0 && amount==0 && value==0 && base==0 && (if(command==72)card in 1..260 && !preview && target==0
            else card==0 && (if(command==89)preview || target>0 else !preview && target==0)))
        // U-15 transport: 28 base=source, target=destination, amount=supplies, value=unit kind (0xffff none),
        // argument=unit count; 29 target=package; 65 argument=transport outfit, target=package. Previews list packages.
        else if(transport)require(if(command==65)card in setOf(44,180) && argument>0 && value==0 && base==0 && amount==0 &&
                (preview || target>0)
            else card in setOf(24,25,150) && (if(command==28)!preview && base in 1..65535 && target in 1..65535 &&
                (value==0xffff) == (argument==0) && argument in 0..65535 && (value==0xffff || value in 0..199 || value in 1000..1015)
                else value==0 && base==0 && argument==0 && amount==0 && (preview || target>0)))
        else if(command==61)require(value==0 && base==0 && card in setOf(39,44,47,50,59,175,180,183,186,195) &&
            (if(card in setOf(59,195))target==0 else target>0))
        else require(value==0 && base==0 && card in setOf(0,59,195))
        val listed=personnel || operation || market || transport || proposal || justice || espionage || coup || funds || goal || trade || production || strike || succession || command in setOf(10,11,12,13,87)
        return Query(character,command,card,if(command==65)argument else if(listed)0 else target,message.copyOfRange(2,message.size),
            if(listed)target else 0,base,value,preview,amount,argument)
    }
    private fun tradeQuoteArgument(preview:Boolean,target:Int,argument:Int)=argument==0 ||
        target>0 && (if(preview)argument==-1 else argument>0)
    /** Status lives in the otherwise unused rank field; no original success ACK is forged. */
    data class CivicStatus(val phase:Int,val outfit:Int,val base:Int,val readyAt:Long,val effect:Int)
    fun response(q:Query,status:Int,changed:Boolean?=null,civic:CivicStatus?=null,outcome:Int=0,found:Int=0,report:Int=0,
        fundBalance:Long?=null,tradeReportCurrency:StrategyCurrencyLabel?=null,evaluationBalance:Long?=null):List<ByteArray> {
        require(status in 0..65535 && outcome in 0..3 && found>=0)
        require(tradeReportCurrency==null || q.command==20 && q.value==2 && !q.preview && q.argument>0 && status==0 && outcome==1)
        val payload=q.raw.copyOf();ByteBuffer.wrap(payload).putShort(10,status.toShort())
        // 逮捕命令 result (U-25) in the argument word: 1 arrested, 2 evaded, 3 failed.
        if(outcome>0)ByteBuffer.wrap(payload).putInt(17,outcome)
        // 一斉捜索's found spot in the base word.
        if(found>0)ByteBuffer.wrap(payload).putInt(23,found)
        if(fundBalance!=null) {
            require(q.command==95 && !q.preview && status==0 && fundBalance in 0..0xffffffffL)
            ByteBuffer.wrap(payload).putInt(23,fundBalance.toInt())
        }
        // 情報工作 report (approval*256+peace) in the amount word.
        if(report>0)ByteBuffer.wrap(payload).putShort(21,report.toShort())
        if(evaluationBalance!=null) {
            require(!q.preview && status==0 && changed==true && report==0 && found==0)
            writeEvaluationBalance(q,payload,evaluationBalance)
        }
        if(!q.preview && changed!=null)payload[16]=(if(changed)2 else 3).toByte()
        if(civic!=null && status==0) {
            require(q.command in setOf(52,54,55,56) && civic.phase in -1..5 && civic.outfit>=0 && civic.base in 0..65535 &&
                civic.readyAt in 0..0xffffffffL && civic.effect in 0..65535)
            val buffer=ByteBuffer.wrap(payload)
            payload[16]=(if(civic.phase<0)3 else 10+civic.phase).toByte()
            buffer.putInt(12,civic.outfit);buffer.putInt(17,civic.readyAt.toInt());buffer.putShort(21,civic.effect.toShort())
            buffer.putInt(23,civic.base)
        }
        return tradeReportCurrency?.records(q.raw).orEmpty() +
            listOf(LoginMessages.sessionMessage(0x1200,payload),LoginMessages.sessionMessage(0x1201,byteArrayOf()))
    }
    fun candidates(q:Query,characters:List<AppointmentDirectoryMessages.Person>):List<ByteArray> {
        require(q.preview)
        return AppointmentDirectoryMessages.people(AppointmentDirectoryMessages.Query(q.character,SEARCH,q.command,q.card,q.base,q.raw),characters)
    }
    data class Selection(val id:Int,val name:String,val rank:Int=0)
    private fun tradeRecord(q:Query,flag:Int,status:Int=0,amount:Int=0,tariff:Int=0):ByteArray {
        val payload=q.raw.copyOf()
        ByteBuffer.wrap(payload).putShort(10,status.toShort()).put(16,flag.toByte()).putShort(21,tariff.toShort()).putInt(23,amount)
        return LoginMessages.sessionMessage(0x1200,payload)
    }
    fun tradeQuote(q:Query,status:Int,amount:Int,tariff:Int,currency:StrategyCurrencyLabel?):List<ByteArray> {
        require(q.isTradeQuote)
        return currency?.records(q.raw).orEmpty() + tradeRecord(q,20,status,amount,tariff) +
            LoginMessages.sessionMessage(0x1201,byteArrayOf())
    }
    data class ProductionProgress(val yard:Int,val buildingUntil:Long,val kind:Int?,val shipReadyAt:Long,val stock:Int,
        val automaticPausedUntil:Long) {
        init {require(yard in 0..3 && buildingUntil in 0..0xffffffffL && shipReadyAt in 0..0xffffffffL &&
            (kind==null && shipReadyAt==0L || kind!=null && kind in 0..199) && stock in 0..0xffffff && automaticPausedUntil in 0..0xffffffffL)}
    }
    fun productionQuote(q:Query,status:Int,base:Int,mcp:Int,hours:Int,treasury:Long,mineral:Long,supplies:Long,
        food:Int,adults:Int,currency:StrategyCurrencyLabel?,progress:ProductionProgress?=null):List<ByteArray> {
        require(q.isProductionQuote && base in 0..65535 && mcp in 0..65535 && hours in 0..65535 &&
            listOf(treasury,mineral,supplies).all {it in 0..0xffffffffL} && food>=0 && adults>=0)
        fun row(flag:Int,target:Int,argument:Int,amount:Int,site:Int,result:Int=0):ByteArray {
            val payload=q.raw.copyOf()
            ByteBuffer.wrap(payload).putShort(10,result.toShort()).putInt(12,target).put(16,flag.toByte())
                .putInt(17,argument).putShort(21,amount.toShort()).putInt(23,site)
            return LoginMessages.sessionMessage(0x1200,payload)
        }
        // Older production windows already ignore flag 19, then finish the quote on flag 20.
        val progressRow=progress?.let {row(19,it.buildingUntil.toInt(),it.shipReadyAt.toInt(),
            (it.kind ?: -1)+1,(it.yard shl 24) or it.stock)}
        // The resource row's unused words carry the automatic production deadline, low and high respectively.
        val pausedUntil=progress?.automaticPausedUntil?.toInt() ?: 0
        return currency?.records(q.raw).orEmpty() + listOfNotNull(progressRow) +
            row(21,mineral.toInt(),supplies.toInt(),pausedUntil and 0xffff,food,pausedUntil ushr 16) +
            row(20,adults,treasury.toInt(),hours,(mcp shl 16) or base,status) +
            LoginMessages.sessionMessage(0x1201,byteArrayOf())
    }
    /** Same native 0055ba80 rows, with explicit selection identities instead of forged characters. */
    fun selections(q:Query,rows:List<Selection>,fundBalance:Long?=null,currency:StrategyCurrencyLabel?=null,
        evaluationBalance:Long?=null):List<ByteArray> {
        require(q.preview && q.command in setOf(10,11,12,13,15,20,21,39,40,35,36,233,19,24,29,65,68,69,70,71,73,74,75,80,81,87,89,95,96,98) && rows.size<=200)
        val bytes=java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use {out->
            out.writeByte(rows.size)
            for(row in rows) {
                require(row.id>0 && row.name.length<=12 && '\u0000' !in row.name && row.rank in 0..20)
                out.writeInt(row.id);out.writeByte(row.name.length+1);(row.name+'\u0000').forEach {out.writeChar(it.code)}
                out.writeShort(row.rank);out.writeShort(0);out.writeByte(0);out.writeByte(0);out.writeByte(0)
                out.writeInt(0);out.writeInt(0)
            }
        }
        val header=if(evaluationBalance!=null)q.raw.copyOf().also {
            require(fundBalance==null)
            writeEvaluationBalance(q,it,evaluationBalance)
        } else if(fundBalance==null)q.raw else q.raw.copyOf().also {
            require(q.command==95 && fundBalance in 0..0xffffffffL)
            ByteBuffer.wrap(it).putInt(17,1).putInt(23,fundBalance.toInt())
        }
        require(currency==null || fundBalance!=null)
        // Old clients finish the pending preview at 1202 and safely ignore the following optional labels.
        val extras=if(q.hasTradeQuote)listOf(tradeRecord(q,19)) else currency?.records(q.raw).orEmpty()
        return listOf(LoginMessages.sessionMessage(0x1200,header),LoginMessages.sessionMessage(0x1202,bytes.toByteArray())) +
            extras + LoginMessages.sessionMessage(0x1201,byteArrayOf())
    }
    /** Market replies reuse the empty amount word as a marker, leaving the argument (market mode) intact. */
    private fun writeEvaluationBalance(q:Query,payload:ByteArray,balance:Long) {
        require(q.spendsEvaluation && balance in 0..0xffffffffL)
        ByteBuffer.wrap(payload).putShort(21,1).putInt(23,balance.toInt())
    }
}
