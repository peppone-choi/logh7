package org.logh7.gateway

import org.logh7.engine.MailIdentity
import org.logh7.engine.Messenger
import org.logh7.protocol.MailMessages
import org.logh7.protocol.MessengerMessages as Wire

/** GameCommunication의 직렬 실행/잠금 안에서만 사용한다. 작성: Peppone Choi. */
internal class GameMessenger(private val presence:GamePresence,private val generation:Long,
    currentGeneration:()->Long,private val identity:(Long,Int)->MailIdentity?) {
    private val model=Messenger(generation,currentGeneration)
    private val leases=linkedMapOf<String,Messenger.Endpoint>()
    private val statuses=mutableMapOf<Int,Int>()
    private fun endpoint(member:GamePresence.Member)=Messenger.Endpoint(
        checkNotNull(identity(generation,member.character.id.toInt())).account,
        member.character.id.toInt(),member.connection)
    private fun peer(endpoint:Messenger.Endpoint):MailMessages.Peer {
        val p=checkNotNull(identity(generation,endpoint.character))
        check(p.account==endpoint.account)
        return p.communicationPeer()
    }
    private fun directory(owner:Messenger.Endpoint):ByteArray {
        // Keep callers and the current peer visible before filling the CD's slots.
        val important=model.callerList(owner)+listOfNotNull(model.partner(owner))
        // 00544bf4 finds self in this same list to enable the native call UI.
        val values=(listOf(owner)+important+model.directory(owner).sortedBy {it.character}).distinctBy {it.character}.take(100)
        return Wire.directory(values.map {Wire.Status(peer(it),statuses[it.character] ?: 2)})
    }
    private fun send(target:Messenger.Endpoint,packets:List<ByteArray>) {
        if(leases[target.connection]==target)
            presence.communicationSend(generation,target.character,packets,"")
    }
    private fun publish(events:List<Messenger.Event>) {
        val packets=linkedMapOf<Messenger.Endpoint,MutableList<ByteArray>>()
        fun add(a:Messenger.Endpoint,b:Messenger.Endpoint,packet:ByteArray) {
            packets.getOrPut(a){mutableListOf()}.add(packet)
            packets.getOrPut(b){mutableListOf()}.add(packet)
        }
        for(event in events)when(event) {
            is Messenger.Event.Calling->add(event.from,event.to,
                Wire.link(Wire.Link(false,peer(event.from),peer(event.to),2,0)))
            is Messenger.Event.Connected->add(event.first,event.second,
                Wire.link(Wire.Link(false,peer(event.first),peer(event.second),4,1)))
            is Messenger.Event.Cancelled->add(event.from,event.to,
                Wire.link(Wire.Link(true,peer(event.from),peer(event.to),0,0)))
            is Messenger.Event.Disconnected->add(event.first,event.second,
                Wire.link(Wire.Link(true,peer(event.first),peer(event.second),0,0)))
            is Messenger.Event.Message->add(event.from,event.to,
                Wire.text(Wire.Text(0,peer(event.from),event.text)))
        }
        packets.forEach { (owner,rows)->send(owner,rows) }
    }
    private fun synchronize() {
        val live=presence.communicationMembers(generation).map(::endpoint).associateBy {it.connection}
        for(old in leases.values.toList())if(live[old.connection]!=old) {
            leases.remove(old.connection);statuses.remove(old.character);publish(model.offline(old))
        }
        for(next in live.values)if(leases[next.connection]!=next) {
            publish(model.online(next));leases[next.connection]=next
        }
    }
    fun handle(connection:String,body:ByteArray):List<ByteArray> {
        synchronize()
        val owner=checkNotNull(leases[connection])
        val opcode=java.nio.ByteBuffer.wrap(body).short.toInt() and 65535
        fun rejected(text:String)=listOf(MailMessages.rejected(opcode,text))
        when(opcode) {
            0x0f06->{require(body.size==2);return listOf(directory(owner))}
            0x0f0d->{
                val command=Wire.status(body)
                // CD 005440d1 clears the peer and sets only its trailing status.
                require(command.peer.id in listOf(0,owner.character) && command.peer.personal && command.status in 0..2)
                statuses[owner.character]=command.status
                // A disabled recipient cannot retain an active conversation.
                if(command.status==2) {
                    publish(model.disconnect(owner))
                    model.callerList(owner).forEach {publish(model.dismiss(owner,it.character))}
                }
                val packet=Wire.status(command.copy(peer=peer(owner)))
                leases.values.forEach {send(it,if(it==owner)listOf(packet,directory(it)) else listOf(directory(it)))}
                return emptyList()
            }
            0x0f0e->{
                val command=Wire.link(body)
                require(command.first.personal && command.second.personal)
                val events=if(command.close) {
                    require(command.first.id==owner.character || command.second.id==owner.character || command.second.id==0)
                    val target=if(command.first.id==owner.character)command.second.id else command.first.id
                    model.dismiss(owner,target)
                } else when(command.phase) {
                    1->{
                        require(command.first.id==owner.character && command.result==0)
                        if((statuses[owner.character] ?: 2)!=0 || (statuses[command.second.id] ?: 2)!=0)
                            return rejected("현재 메신저가 수신 거부 상태입니다. 상태를 확인해 주세요.")
                        if(leases.values.none {it.character==command.second.id})
                            return rejected("상대가 접속 중이 아닙니다. 목록을 다시 확인해 주세요.")
                        if(command.second.id==owner.character)return rejected("자기 자신에게 호출할 수 없습니다.")
                        model.call(owner,command.second.id)
                    }
                    3->{
                        require(command.second.id==owner.character && command.result in 1..2)
                        if((statuses[owner.character] ?: 2)!=0)return rejected("현재 메신저가 수신 거부 상태입니다.")
                        if(model.callerList(owner).none {it.character==command.first.id})
                            return rejected("이미 취소되거나 종료된 호출입니다.")
                        if(command.result==1)model.accept(owner,command.first.id) else model.dismiss(owner,command.first.id)
                    }
                    else->error("Invalid messenger connection phase")
                }
                publish(events)
                return emptyList()
            }
            0x0f0f->{
                val command=Wire.text(body)
                require(command.id==0 && command.peer.personal)
                // 00543ce7 copies self (context+1d10); the recipient is the accepted partner.
                require(command.peer.id==owner.character)
                val target=model.partner(owner)
                if(target==null)
                    return rejected("연결된 상대가 아닙니다. 상대를 호출하고 수락을 기다려 주세요.")
                if(command.text.isBlank() || command.text.any {it.isSurrogate() || it=='\u0000' ||
                    (it<' ' && it !in "\t\r\n") || it=='\u007f'})return rejected("보낼 내용을 확인해 주세요.")
                publish(listOf(model.send(owner,command.text)))
                return emptyList()
            }
            else->error("Unsupported messenger opcode")
        }
    }
    fun disconnected(connection:String) {
        val owner=leases.remove(connection) ?: return
        statuses.remove(owner.character);publish(model.offline(owner))
        leases.values.forEach {send(it,listOf(directory(it)))}
    }
}
