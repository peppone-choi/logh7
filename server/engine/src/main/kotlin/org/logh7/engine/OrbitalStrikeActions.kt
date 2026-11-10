package org.logh7.engine

/**
 * U-31 軌道対地攻撃 (orbital ground attack, user decision 2026-11-02 "새 버튼 만들어서 구현"). The CD has no command
 * for it (only the P13 patch notice announced it), so the compatibility DLL lights the empty commander-tab slot 21 and
 * sends this request over the 0x7f00 strategy channel. Every rule below is evidence:guess:
 * a character in tactics, not wounded, whose flagship is within [RANGE] of an enemy planet's surface in the same grid,
 * spends [SUPPLIES] of its flagship's supplies; each defending ground unit there loses [DAMAGE] strength (removed at 0)
 * and the planet's public order falls by [PEACE]. Each operational ship unit the character commands within [RANGE] of the same
 * planet joins the attack and adds another [DAMAGE] (at most [MAX_ATTACKERS] attackers in all).
 * A planet with a running ground battle refuses the attack (user decision 2026-11-02, COOLDOWN).
 * A garrison saved only as quantities gets per-unit identities first (GroundUnits.reorganize).
 * The result's outcome is the number of attackers (>= 1 on success), report the destroyed defenders.
 * Author: Peppone Choi.
 */
object OrbitalStrikeActions {
    const val STRIKE=230
    const val QUOTE_PHASE=1
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
        if(p==null || i.country==null || p.generation!=i.generation || p.characterId!=i.characterId || p.country!=i.country || member.country!=i.country)
            return reject(SocialRejection.IDENTITY)
        if(i.country in session.eliminated)return reject(SocialRejection.SESSION)
        if(BattleDeath.deceased(s,p.characterId))return reject(SocialRejection.IDENTITY)
        if(!p.cpPaused || p.baseId!=0 || p.mode!=5 || p.posture?.mode?.requiresBase==true)
            return reject(SocialRejection.LOCATION)
        if(p.hull?.woundedUntil?.let {it>now}==true || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        if(p.supplies<SUPPLIES)return reject(SocialRejection.MAXIMUM)
        val own=i.country
        val camp=CoupActions.camp(s,p.characterId,p.country)
        val at=(p.motion?.pose(now,p.posture,g,s) ?: TacticalDeployment.initial(p.unitId,p.grid,players,s,g)).position
        val base=g.bases.filter {b->b.grid==p.grid && s.bases[b.id]?.let {
            it.country!=null && it.country !in session.eliminated && (it.country!=own || it.camp!=camp)
        }==true}
            .map {b->b to TacticalBasePosition.at(b,now,s).let {c->kotlin.math.hypot((c.x-at.x).toDouble(),(c.y-at.y).toDouble())-b.diameter}}
            .filter {it.second<=RANGE}.minByOrNull {it.second}?.first ?: return reject(SocialRejection.TARGET)
        val owner=s.bases.getValue(base.id)
        // User decision 2026-11-02: no bombardment while a ground battle runs on that planet (preparation fire only).
        if(base.id in s.groundBattles ||
            GroundBattleOperations.sides(s,base).count {it.first.first !in session.eliminated}>1)
            return reject(SocialRejection.COOLDOWN)
        val centre=TacticalBasePosition.at(base,now,s)
        fun inRange(x:Float,y:Float)=kotlin.math.hypot((centre.x-x).toDouble(),(centre.y-y).toDouble())-base.diameter<=RANGE
        val escorts=s.outfits.values.filter {TacticalAuthority.camp(it,s)==TacticalAuthority.camp(p,s) && it.positionIn(g)==OutfitPosition(p.grid)}.sumOf {o->
            o.activeShips?.values.orEmpty().count {u->u.boats>u.damaged && !s.tacticalCommands.confused(u.id) && TacticalAuthority.commander(o,u,s)==p.characterId &&
                u.tacticalPose(p.grid,now,players,s,g).position.let {inRange(it.x,it.y)}}}
        val attackers=minOf(MAX_ATTACKERS,1+escorts)
        if(r.phase==QUOTE_PHASE)return SocialResult(p,s,outcome=attackers,found=base.id)
        if(i.base!=0 && (i.base!=base.id || i.argument!=attackers || i.value!=SUPPLIES))return reject(SocialRejection.QUOTE_CHANGED)
        val damage=DAMAGE*attackers
        val destroyed=mutableSetOf<Int>()
        // A legacy garrison (quantities only) first gets identities at full strength, as ground battle would need.
        var cursor=s.nextShipId
        val known=s.outfits.mapValues {(_,o)->
            if(o.country!=owner.country || CoupActions.outfitCamp(s,o)!=owner.camp || o.activeGround!=null || o.kind!=OutfitKind.GROUND ||
                (o.position?.baseId ?: o.baseId)!=base.id)o
            else GroundUnits.reorganize(o,o.units,o.activeShips,cursor).let {(roster,next)->cursor=next;o.copy(activeGround=roster)}
        }
        val outfits=known.mapValues {(_,o)->
            val ground=o.activeGround
            // Only the planet holder's camp defends; a revolt's other camp may have troops landed there too.
            if(o.country!=owner.country || CoupActions.outfitCamp(s,o)!=owner.camp || ground==null || ground.values.none {it.base==base.id})return@mapValues o
            val roster=ActiveGroundUnits(ground.values.mapNotNull {u->
                if(u.base!=base.id)u else (u.strength-damage).let {left->if(left<=0){destroyed+=u.id;null} else u.copy(strength=left)}})
            o.copy(activeGround=roster,units=UnitInventory(o.units.ships,o.units.troops.filterKeys {!GroundUnits.isGround(it.kind)}+roster.quantities(),o.units.work))
        }
        val economy=owner.economy?.let {it.copy(peace=maxOf(0,it.peace-PEACE))}
        // CD manual p.46: a fortress also loses armor, and a mobile fortress its propulsion (FortressArmor).
        val commands=s.tacticalCommands
        val remaining=if(destroyed.isEmpty())commands else commands.copy(
            morale=commands.morale-destroyed,
            groundTargets=commands.groundTargets.filter {it.key !in destroyed && it.value !in destroyed})
        val next=FortressArmor.strike(base,s.copy(outfits=outfits,nextShipId=cursor,
            bases=s.bases+(base.id to owner.copy(economy=economy)),tacticalCommands=remaining),attackers)
        return SocialResult(p.copy(supplies=p.supplies-SUPPLIES),next,changed=true,outcome=attackers,found=base.id,report=destroyed.size)
    }
}
