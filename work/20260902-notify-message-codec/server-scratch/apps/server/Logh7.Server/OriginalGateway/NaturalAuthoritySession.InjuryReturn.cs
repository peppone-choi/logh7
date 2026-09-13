using Logh7.Server.Storage;
using System.Security.Cryptography;
using System.Text;

namespace Logh7.Server.OriginalGateway;

public sealed partial class NaturalAuthoritySession
{
    private OriginalGridUnitRecord? _persistedGridUnit;
    private bool IsRecoveringFromInjury => _persistedGridUnit?.InjuryReturnId is not null;
    private long CurrentShipGeneration => _persistedGridUnit?.ShipGeneration ?? 0;
    private bool HasCurrentShipIncarnation => _battles.IsCurrentShipGeneration(_worldGridUnitId,CurrentShipGeneration);

    private Func<OriginalTacticalDamageState,CancellationToken,Task>? BindOwnDamagePersistence()
    {
        if(_persistedGridUnit is not OriginalGridUnitRecord unit) return null;
        var accountId=_accountId;
        var store=_store;
        var unitNumber=_battles.GetEncounter(unit.CurrentCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number).UnitNumber(unit.UnitId);
        // Capture this presence's identity, not mutable session fields. A later
        // reconnect or incarnation must not redirect an already accepted hit.
        return async (damage,ct) =>
        {
            var result=await store.SaveOriginalUnitDamageAsync(accountId,
                new(unit.CharacterId,unit.UnitId,unit.CurrentCellId,unit.ShipGeneration,
                    unitNumber,
                    damage.Damaged,damage.Destroyed),ct);
            if(result.Unit.CharacterId!=unit.CharacterId || result.Unit.UnitId!=unit.UnitId ||
                result.Unit.CurrentCellId!=unit.CurrentCellId || result.Unit.ShipGeneration!=unit.ShipGeneration ||
                result.Unit.Damaged!=damage.Damaged || result.Unit.Destroyed!=damage.Destroyed)
                throw new InvalidOperationException("DAMAGE_PERSISTED_STATE_MISMATCH");
            // The victim session refreshes its version on its next command.
            // Do not mutate that session concurrently from the NPC loop.
        };
    }

    private async Task<string?> PrepareFlagshipRecoveryAsync(CancellationToken cancellationToken)
    {
        if (_persistedGridUnit is not OriginalGridUnitRecord { InjuryReturnId: Guid returnId } unit)
            return null;
        var number=OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        using var lease=await _battles.LockAsync(unit.CurrentCellId,number,cancellationToken);
        // This authored recovery implementation admits quiet friendly bases
        // only. Do not resurrect a combat participant in a still-active fight.
        if (!ActiveBattlefieldCatalog.Resolve(unit.CurrentCellId).ProjectBaseInformation(unit.CurrentCellId)
                .Any(b=>b.Id==unit.BaseId && b.Power==_createdCharacter?.Power && b.Camp==0) ||
            HasUnsafeBattlefield(unit.CurrentCellId,unit.UnitId,_createdCharacter?.Power ?? 0))
            return null;
        OriginalInjuryReturnStoreResult result;
        try
        {
            result=await _store.RecoverOriginalFlagshipAsync(_accountId,unit.CharacterId,unit.UnitId,
                returnId,unit.AuthorityVersion,cancellationToken);
        }
        catch (NotSupportedException) { return null; } // Compatibility stores retain their injury state.
        var recovered=result.Unit;
        if (recovered.CharacterId!=unit.CharacterId || recovered.UnitId!=unit.UnitId ||
            recovered.CurrentCellId!=unit.CurrentCellId || recovered.BaseId!=unit.BaseId ||
            recovered.ShipGeneration!=checked(unit.ShipGeneration+1) ||
            recovered.Damaged!=0 || recovered.Destroyed!=0 || recovered.InjuryReturnId is not null)
            return "original.flagship.recovery-state-shape";
        var character=(await _store.ListCharactersAsync(_accountId,cancellationToken))
            .SingleOrDefault(c=>c.CharacterId==unit.CharacterId);
        if (character is null) return "original.flagship.character-unavailable";
        _createdCharacter=RestoreCharacter(character);
        ApplyPersistedGridUnit(recovered);
        _receipt.Record("flagship-recovery",$"unit-{unit.UnitId};generation-{recovered.ShipGeneration};kind-{character.FlagshipKind}",
            _accountId);
        return null;
    }

    private void ApplyPersistedGridUnit(OriginalGridUnitRecord unit)
    {
        if (unit.UnitNumber == 0 || unit.Damaged > unit.UnitNumber ||
            unit.Destroyed > unit.Damaged)
            throw new InvalidOperationException("INJURY_RETURN_PERSISTED_LOSS_SHAPE");
        if (!_battles.ObserveShipGeneration(unit.UnitId,unit.ShipGeneration))
            throw new InvalidOperationException("SHIP_GENERATION_STALE");
        if (CurrentShipGeneration != unit.ShipGeneration)
        {
            _tacticalUnitShip = null;
            _tacticalPlayerCorps = null;
            _npcSceneImportedGrid = null;
            _npcSceneBootstrapGrid = null;
            _participantPublished = false;
            _battles.RemoveParticipant(PendingNotifications.Writer);
            _battleSubscription?.Dispose();
            _battleSubscription = null;
            _subscribedBattleGrid = null;
            _battleSubscriptionId = Guid.Empty;
        }
        if (_worldGridCellId != unit.CurrentCellId)
            _tacticalUnitShip = null;
        _worldGridCellId = unit.CurrentCellId;
        _worldGridBaseId = unit.BaseId;
        _persistedGridUnit = unit;
        var encounter = _battles.GetEncounter(unit.CurrentCellId,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number);
        encounter.RegisterUnitNumber(unit.UnitId,unit.UnitNumber);
        if (!encounter.ObserveShipGeneration(unit.UnitId,unit.ShipGeneration,new(unit.Damaged,unit.Destroyed)))
            throw new InvalidOperationException("SHIP_GENERATION_STALE");
    }

    private async Task<string?> PrepareInjuryReturnAsync(CancellationToken cancellationToken)
    {
        var error = await RestorePersistedGridUnitAsync(cancellationToken,allowNewIncarnation:true);
        if (error is not null) return error;
        // Older in-memory compatibility stores have no persisted unit row.
        // This branch is not an alternative durable recovery implementation.
        if (_persistedGridUnit is not OriginalGridUnitRecord unit || unit.InjuryReturnId is not null)
            return null;
        var number = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number;
        var encounter = _battles.GetEncounter(unit.CurrentCellId,number);
        if (encounter.HasSurvivors(unit.UnitId)) return null;
        var preference = _createdCharacter?.ReturnBaseId ?? 0;
        if (preference == 0)
            return "original.injury-return.birthplace-unavailable";
        var destination = ActiveBattlefieldCatalog.StaticBases?.SingleOrDefault(b => b.Id == preference);
        if (destination is null || !ActiveBattlefieldCatalog.Resolve(destination.Grid)
            .ProjectBaseInformation(destination.Grid).Any(b =>
                b.Id == preference && b.Power == _createdCharacter?.Power && b.Camp == 0))
            return "original.injury-return.destination-unavailable";
        // Same-field docked participation needs its original character-state
        // contract recovered. Do not fake a global battle-end to support it.
        var grid = destination.Grid;
        if (grid == unit.CurrentCellId) return "original.injury-return.destination-not-quiet";
        // Consistent order also permits concurrent returns in opposite directions.
        using var firstLease = await _battles.LockAsync(Math.Min(unit.CurrentCellId,grid),number,cancellationToken);
        using var secondLease = await _battles.LockAsync(Math.Max(unit.CurrentCellId,grid),number,cancellationToken);
        if (!HasCurrentShipIncarnation) return "original.flagship.scene-refresh-required";
        var loss = encounter.GetUnitDamage(unit.UnitId);
        var unitNumber = encounter.UnitNumber(unit.UnitId);
        if (loss.Destroyed < unitNumber) return null;
        if (HasUnsafeBattlefield(grid,unit.UnitId,_createdCharacter?.Power ?? 0))
            return "original.injury-return.destination-not-quiet";
        OriginalInjuryReturnStoreResult result;
        // Generation0 retains the already-persisted E090 encounter identity.
        // Later lives in the same battle must not collide with its old replay key.
        var defeatId=unit.ShipGeneration == 0 ? encounter.Id : new Guid(SHA256.HashData(Encoding.UTF8.GetBytes(
            FormattableString.Invariant($"original-defeat/v2|{encounter.Id:N}|{unit.UnitId}|{unit.ShipGeneration}"))).AsSpan(0,16));
        try
        {
            result = await _store.ReturnInjuredOriginalUnitAsync(_accountId,
                new(defeatId,unit.CharacterId,unit.UnitId,unit.AuthorityVersion,unit.CurrentCellId,
                    grid,preference,unitNumber,loss.Damaged,loss.Destroyed),cancellationToken);
        }
        catch (NotSupportedException)
        {
            return "original.injury-return.persistence-not-supported";
        }
        var returned = result.Unit;
        if (returned.CharacterId != unit.CharacterId || returned.UnitId != unit.UnitId ||
            returned.CurrentCellId != grid || returned.BaseId != preference ||
            returned.Damaged != loss.Damaged || returned.Destroyed != loss.Destroyed ||
            returned.InjuryReturnId != defeatId)
            return "original.injury-return.persisted-state-shape";
        // Only after commit: leave the old observation; never complete the
        // surviving participants' encounter or synthesize a replacement ship.
        _battles.RemoveParticipant(PendingNotifications.Writer);
        _battleSubscription?.Dispose();
        _battleSubscription = null;
        _subscribedBattleGrid = null;
        _battleSubscriptionId = Guid.Empty;
        ApplyPersistedGridUnit(returned);
        _receipt.Record("injury-return",$"unit-{unit.UnitId}-base-{preference}-grid-{grid};loss-preserved",_accountId);
        return null;
    }
}
