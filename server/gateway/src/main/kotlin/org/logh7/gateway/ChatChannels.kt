package org.logh7.gateway

import org.logh7.engine.OfficeScope
import org.logh7.engine.OrganizationOffices

/**
 * U-06 channel chat (update02 REVIEW 02, evidence:manual): the CD build has no channel buttons, so a spot-chat
 * line that starts with a channel word goes to that channel instead (user decision 2026-11-01, evidence:guess).
 * 同陣営/両陣営 stay on the tactical grid-chat buttons. Author: Peppone Choi.
 */
internal enum class ChatChannel(val words:List<String>,val label:String) {
    OUTFITTED(listOf("/부대","/unit"),"부대"), UNATTACHED(listOf("/미소속","/free"),"미소속"),
    OWN_OUTFIT(listOf("/자부대","/own"),"자부대"), ADMINISTRATION(listOf("/군정","/gov"),"군정"),
    PROCLAMATION(listOf("/포고","/decree"),"포고");
    companion object {
        /** update02 布告: empire 皇帝·帝国宰相·軍務尚書·統帥本部総長·宇宙艦隊司令長官, alliance 議長·国防委員長·
         * 統合作戦本部長·後方勤務本部長·宇宙艦隊司令長官. */
        val proclamationCards=setOf(1,5,17,22,28,132,135,147,153,169)
        /** The channel and remaining text, or null for an ordinary spot line. */
        fun parse(text:String):Pair<ChatChannel,String>? {
            // English aliases let a player without a Korean IME pick a channel.
            for(channel in entries)for(word in channel.words)
                if(text.equals(word,true) || text.startsWith("$word ",true))return channel to text.substring(word.length).trim()
            return null
        }
    }
    /** 軍政: holders of central, fortress, planet-government and capital offices (not outfit posts). */
    fun admits(cards:Collection<Int>,outfit:Int):Boolean = when(this) {
        OUTFITTED,OWN_OUTFIT -> outfit>0
        UNATTACHED -> outfit==0
        ADMINISTRATION -> cards.any {OrganizationOffices.of(it)?.let {o->o.camp==0 && o.scope!=OfficeScope.OUTFIT}==true}
        PROCLAMATION -> cards.any {it in proclamationCards}
    }
}
