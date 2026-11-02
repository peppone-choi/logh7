package org.logh7.engine

/**
 * U-31 軌道対地攻撃 (orbital ground attack, user decision 2026-11-02 "새 버튼 만들어서 구현"). The CD has no command
 * for it (only the P13 patch notice announced it), so the compatibility DLL lights the empty commander-tab slot 21 and
 * sends this request over the 0x7f00 strategy channel. Every rule below is evidence:guess:
 * a character in tactics, not wounded, whose flagship is within [RANGE] of an enemy planet's surface in the same grid,
 * spends [SUPPLIES] of its flagship's supplies; each defending ground unit there loses [DAMAGE] strength (removed at 0)
 * and the planet's public order falls by [PEACE]. Each living ship unit the character commands within [RANGE] of the same
 * planet joins the attack and adds another [DAMAGE] (at most [MAX_ATTACKERS] attackers in all).
 * The result's outcome is the number of attackers (>= 1 on success), report the destroyed defenders.
 * Author: Peppone Choi.
 */
object OrbitalStrikeActions {
    const val STRIKE=230
    val commands=setOf(STRIKE)
    const val RANGE=20f
    const val SUPPLIES=100
    const val DAMAGE=150
    const val PEACE=2
    const val MAX_ATTACKERS=10

    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,players:Collection<Navigator>):SocialResult {
        val i=r.identity;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || p.generation!=i.generation || p.characterId!=i.characterId || p.power!=i.power || member.power!=i.power)
            return reject(SocialRejection.IDENTITY)
        if(!p.cpPaused || p.baseId!=0 || p.mode!=5)return reject(SocialRejection.LOCATION)
        if(p.hull?.woundedUntil?.let {it>now}==true || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        if(p.supplies<SUPPLIES)return reject(SocialRejection.MAXIMUM)
        val own=if(p.power==Power.EMPIRE)2 else 3
        val camp=CoupActions.camp(s,p.characterId)
        val at=(p.motion?.pose(now,p.posture,g,s) ?: TacticalDeployment.initial(p.unitId,p.power,p.grid,players,s,g)).position
        val base=g.bases.filter {b->b.grid==p.grid && s.bases[b.id]?.let {it.power in 2..3 && (it.power!=own || it.camp!=camp)}==true}
            .map {b->b to TacticalBasePosition.at(b,now,s).let {c->kotlin.math.hypot((c.x-at.x).toDouble(),(c.y-at.y).toDouble())-b.diameter}}
            .filter {it.second<=RANGE}.minByOrNull {it.second}?.first ?: return reject(SocialRejection.TARGET)
        val owner=s.bases.getValue(base.id)
        val centre=TacticalBasePosition.at(base,now,s)
        fun inRange(x:Float,y:Float)=kotlin.math.hypot((centre.x-x).toDouble(),(centre.y-y).toDouble())-base.diameter<=RANGE
        val escorts=s.outfits.values.filter {it.power==own && it.positionIn(g)==OutfitPosition(p.grid)}.sumOf {o->
            o.activeShips?.values.orEmpty().count {u->u.boats>0 && TacticalAuthority.commander(o,u,s)==p.characterId &&
                (u.tactical?.motion?.pose(now,u.posture,g,s)?.position ?: TacticalDeployment.initial(u.id,p.power,p.grid,players,s,g).position)
                    .let {inRange(it.x,it.y)}}}
        val attackers=minOf(MAX_ATTACKERS,1+escorts);val damage=DAMAGE*attackers
        var destroyed=0
        val outfits=s.outfits.mapValues {(_,o)->
            val ground=o.activeGround
            if(o.power!=owner.power || ground==null || ground.values.none {it.base==base.id})return@mapValues o
            val roster=ActiveGroundUnits(ground.values.mapNotNull {u->
                if(u.base!=base.id)u else (u.strength-damage).let {left->if(left<=0){destroyed++;null} else u.copy(strength=left)}})
            o.copy(activeGround=roster,units=UnitInventory(o.units.ships,o.units.troops.filterKeys {!GroundUnits.isGround(it.kind)}+roster.quantities(),o.units.work))
        }
        val economy=owner.economy?.let {it.copy(peace=maxOf(0,it.peace-PEACE))}
        val next=s.copy(outfits=outfits,bases=s.bases+(base.id to owner.copy(economy=economy)))
        return SocialResult(p.copy(supplies=p.supplies-SUPPLIES),next,changed=true,outcome=attackers,found=base.id,report=destroyed)
    }
}
