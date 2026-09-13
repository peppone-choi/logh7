using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalNpcStep(OriginalTacticalUnitShipRecord Ship, uint TargetId, byte? Arms);

// Authored temporary NPC policy; not a recovered original-server AI.
public sealed class OriginalTacticalNpcController(
    OriginalTacticalParticipantSnapshot initial,
    OriginalStaticUnitShipCapabilities capabilities,
    OriginalStaticArmsTable arms)
{
    public OriginalTacticalParticipantSnapshot Snapshot { get; private set; } = initial;
    public float MovementVelocity => capabilities.Speed;
    public const uint FireIntervalTicks = 72; // NEW DESIGN: three seconds, including each target acquisition delay.
    private uint? _lastTick;
    private uint _lastShot;
    private uint _target;

    private bool _autonomousControl = true;

    // Process-owned engagement latch for an authored defensive outfit. Persisted
    // casualties from an earlier incarnation do NOT count as provocation: a
    // restored scene must not silently start a fight the player never began.
    private bool _defensive;
    private bool _provoked;

    public void SetDefensivePosture(bool defensive) => _defensive = defensive;

    // Called only from a committed live hit on this unit, never from a scene
    // restore that merely projects saved damage.
    public void Provoke() => _provoked = true;

    public bool IsHoldingFire => _defensive && !_provoked;

    // Caller holds the grid lease. Refresh public character data only: the
    // persisted scene snapshot may lag current pose, damage, supplies and AI.
    internal bool RefreshCharacter(OriginalTacticalParticipantSnapshot restored)
    {
        if(restored.Unit.Id!=Snapshot.Unit.Id || restored.ShipGeneration!=Snapshot.ShipGeneration ||
            restored.Ship.Character!=Snapshot.Ship.Character || restored.IsHostileTo(Snapshot.Power,Snapshot.Camp))
            return false;
        Snapshot=new(Snapshot.Unit,Snapshot.Ship,Snapshot.Corps,restored.CharacterFrame.ToArray(),
            Snapshot.Power,Snapshot.ShipGeneration,Snapshot.Outfit,restored.CommanderMerit);
        return true;
    }

    internal Action PrepareCorpsUpdate(OriginalTacticalCorpsRecord corps) =>
        PrepareControlAssignment(Snapshot.Ship.Character,corps,_autonomousControl);

    internal Action PrepareSupplyUpdate(uint supplies, OriginalTacticalDamageState damage)
    {
        var next = new OriginalTacticalParticipantSnapshot(
            Snapshot.Unit with { Supplies = supplies, Damaged = damage.Damaged, Destroyed = damage.Destroyed },
            Snapshot.Ship, Snapshot.Corps, Snapshot.CharacterFrame.ToArray(),
            Snapshot.Power, Snapshot.ShipGeneration, Snapshot.Outfit, Snapshot.CommanderMerit);
        return () => Snapshot = next;
    }

    // Projection application only. The assignment owner must resolve the real
    // character/corps and validate/persist permissions before entering here.
    internal void ApplyControlAssignment(uint character, OriginalTacticalCorpsRecord corps, bool autonomous)
        => PrepareControlAssignment(character, corps, autonomous)();

    // Prepare all detached snapshots before any member of a batch is changed.
    // The returned commit must run under the same uninterrupted grid lease.
    internal Action PrepareControlAssignment(uint character, OriginalTacticalCorpsRecord corps, bool autonomous)
    {
        if (character == 0 || corps.Id != character)
            throw new ArgumentException("Tactical controlling character/corps mismatch");
        var changedCharacter = Snapshot.Ship.Character != character;
        var next = new OriginalTacticalParticipantSnapshot(Snapshot.Unit,
            Snapshot.Ship with { Character = character }, corps,
            changedCharacter ? [] : Snapshot.CharacterFrame.ToArray(),
            Snapshot.Power, Snapshot.ShipGeneration, Snapshot.Outfit,
            changedCharacter ? null : Snapshot.CommanderMerit);
        return () =>
        {
            Snapshot = next;
            if (changedCharacter)
            {
                _lastTick = null;
                _lastShot = 0;
                _target = 0;
            }
            SetAutonomousControl(autonomous);
        };
    }

    // Caller holds the grid command lease and owns the assignment transaction.
    // This switches simulation only; it neither grants permission nor changes
    // unit identity, casualties, pose, membership or the controlling character.
    public void SetAutonomousControl(bool enabled)
    {
        if (_autonomousControl == enabled) return;
        _autonomousControl = enabled;
        _lastTick = null;
        _lastShot = 0;
        _target = 0;
    }

    // Registry can persist the proposed pose before exposing any AI state.
    internal (OriginalNpcStep Step, Action Commit, Action CommitFire) PrepareAdvance(uint tick,
        IReadOnlyList<OriginalTacticalParticipantSnapshot> candidates, Func<uint, ushort>? unitNumber = null)
    {
        var pending = new OriginalTacticalNpcController(Snapshot, capabilities, arms)
        {
            _lastTick = _lastTick, _lastShot = _lastShot, _target = _target,
            _autonomousControl = _autonomousControl, _defensive = _defensive, _provoked = _provoked
        };
        var step = pending.Advance(tick, candidates, unitNumber);
        return (step, () =>
        {
            Snapshot = pending.Snapshot;
            _lastTick = pending._lastTick;
            // Acquiring a target starts its reaction delay immediately. A shot
            // starts recharge only after its victim's durable damage commits.
            if (step.Arms is null) _lastShot = pending._lastShot;
            _target = pending._target;
            _provoked = pending._provoked;
        }, () => _lastShot = pending._lastShot);
    }

    public OriginalNpcStep Advance(uint tick, IReadOnlyList<OriginalTacticalParticipantSnapshot> candidates,
        Func<uint, ushort>? unitNumber = null)
    {
        var ship = Snapshot.Ship;
        if (!_autonomousControl) return new(ship, 0, null);
        // A defensive outfit keeps its pose and its clock until it is hit, so
        // the first shot of the encounter belongs to whoever chooses to fire.
        if (IsHoldingFire) return new(ship, 0, null);
        var firstTick = _lastTick is null;
        var previous = _lastTick ?? tick;
        var elapsed = unchecked(tick - previous);
        if ((!firstTick && elapsed == 0) || elapsed > int.MaxValue) return new(ship, _target, null);
        _lastTick = tick;
        // No catch-up teleport or burst after a suspended/overloaded server.
        var seconds = Math.Min(elapsed, 6u) / (float)OriginalGameClock.TicksPerSecond;
        // Original client 004C18C4..004C1930: min(3, sensor*4/30),
        // float32 multipliers, then __ftol (truncate toward zero). v174 live
        // entity+958 is 70 at SENSOR10/template100: retain the float32 product
        // rounding before truncation, not v172's unverified double precision.
        // Deterministic NPC detection still is an authored policy.
        var sensorMultiplier = Math.Min(3, Snapshot.Corps.PowerSensor * 4 / 30) switch
        {
            0 => .5f, 1 => .7f, 2 => .9f, _ => 1f,
        };
        var sensorRange = Math.Max(0, Math.Truncate(capabilities.SearchingRange * sensorMultiplier));
        var possible = candidates.Where(p => p.Unit.Id != ship.Id && p.Unit.Grid == Snapshot.Unit.Grid &&
            p.IsHostileTo(Snapshot.Power, Snapshot.Camp) && p.Unit.Destroyed < (unitNumber?.Invoke(p.Unit.Id) ?? capabilities.Number) && IsFinite(p.Ship) &&
            sensorRange > 0 && Distance(ship, p.Ship) <= sensorRange).ToArray();
        var target = possible.FirstOrDefault(p => p.Unit.Id == _target) ?? possible
            .OrderBy(p => Distance(ship, p.Ship)).ThenBy(p => p.Unit.Id).FirstOrDefault();
        var targetId = target?.Unit.Id ?? 0;
        if (_target != targetId)
        {
            _target = targetId;
            _lastShot = tick;
        }
        // Idle time before the player's scene import must not pre-charge a
        // first shot. Re-acquisition after departure/import gets the same delay.
        if (firstTick) return new(ship, _target, null);
        if (target is null || Snapshot.Unit.Destroyed >= capabilities.Number || !IsFinite(ship))
            return new(ship, 0, null);

        var distance = Distance(ship, target.Ship);
        var weapon = SelectWeapon(distance);
        if (weapon is null) return new(ship, _target, null);
        var (weaponId, mask, range, standOff, canHit) = weapon.Value;
        // E074: wire X/Y are render X/Z. Wire Z is not the planar heading axis.
        var bearing = MathF.Atan2(target.Ship.X - ship.X, target.Ship.Y - ship.Y);
        var approaching = distance == 0 || distance > standOff + .0001f || !canHit;
        var movementBearing = distance < standOff ? Normalize(bearing + MathF.PI) : bearing;
        var desiredHeading = approaching ? movementBearing : Enumerable.Range(0, 6)
            .Where(sector => (mask & (1 << sector)) != 0)
            .Select(sector => Normalize(bearing - sector * MathF.PI / 3))
            .MinBy(heading => MathF.Abs(Normalize(heading - ship.Direction)));
        // NEW DESIGN: proportional engine allocation, symmetric bounded turn.
        // This is not the original client's asymmetric pursuit integrator.
        var engine = Snapshot.Corps.PowerMove / 100f;
        var turn = Math.Max(0, capabilities.Turn) * engine * 24 * seconds;
        var heading = Normalize(ship.Direction + Math.Clamp(Normalize(desiredHeading - ship.Direction), -turn, turn));
        var next = ship with { Direction = heading };
        // Coincident positions have no target direction to normalize. Separate
        // along the chosen heading using the same speed/time bound, then let
        // ordinary stand-off positioning take over on the following tick.
        if (approaching && distance == 0)
        {
            var separation = Math.Min(standOff, Math.Max(0, capabilities.Speed) * engine * 24 * seconds);
            next = next with
            {
                X = ship.X + MathF.Sin(heading) * separation,
                Y = ship.Y + MathF.Cos(heading) * separation,
            };
        }
        if (approaching && MathF.Abs(Normalize(movementBearing - heading)) < .01f && distance > 0)
        {
            var length = Math.Min(MathF.Abs(distance - standOff), Math.Max(0, capabilities.Speed) * engine * 24 * seconds)
                * (distance < standOff ? -1 : 1);
            next = next with
            {
                X = ship.X + (target.Ship.X - ship.X) * length / distance,
                Y = ship.Y + (target.Ship.Y - ship.Y) * length / distance,
            };
        }
        // Use the same identity/finite-coordinate authority boundary as player moves.
        var move = new OriginalTacticalMoveShipCommand(tick, 0, 0,
            [new(ship.Id, ship.Direction, ship.X, ship.Y, ship.Z)], capabilities.Speed, next.Direction,
            [new(next.X, next.Y, next.Z)]);
        var authorized = OriginalTacticalCommandAuthority.AuthorizeMoveShip(move, ship.Id, ship);
        if (!authorized.Accepted) return new(ship, _target, null);
        Snapshot = new(Snapshot.Unit, authorized.State!.Value, Snapshot.Corps, Snapshot.CharacterFrame.ToArray(),
            Snapshot.Power, Snapshot.ShipGeneration, Snapshot.Outfit, Snapshot.CommanderMerit);
        ship = Snapshot.Ship;
        byte? fired = null;
        if (!approaching && canHit && distance > 0 && distance < range &&
            (mask & (1 << Sector(bearing, ship.Direction))) != 0 &&
            unchecked(tick - _lastShot) >= FireIntervalTicks)
        {
            fired = weaponId;
            _lastShot = tick;
        }
        return new(ship, _target, fired);
    }

    private (byte Arms, byte Mask, float Range, float StandOff, bool CanHit)? SelectWeapon(float distance)
    {
        // Authored policy: retain family priority among weapons that can hit
        // at the target's current distance, not merely somewhere in the table.
        // If none can, keep the existing approach policy's first usable family.
        (byte Arms, byte Mask, float Range, float StandOff, bool CanHit)? approachWeapon = null;
        var table = arms.EncodeResponse();
        for (byte family = 0; family < 3; family++)
        {
            if ((family == 0 ? Snapshot.Corps.PowerBeam : Snapshot.Corps.PowerGun) == 0) continue;
            var weapon = OriginalTacticalCommandAuthority.ResolveShotArms(family, capabilities);
            var mask = family switch { 0 => capabilities.BeamAngleMask, 1 => capabilities.GunAngleMask, _ => capabilities.MissileAngleMask };
            if (weapon is null || weapon >= 27 || (mask & 0x3f) == 0) continue;
            for (int bin = 7; bin >= 0; bin--)
                if (BinaryPrimitives.ReadInt16BigEndian(table.AsSpan(6 + (weapon.Value * 8 + bin) * 2)) > 0)
                {
                    var range = (float)(bin + 1);
                    var standOff = range * .8f;
                    // Preserve ordinary approach distances; sparse curves need
                    // a populated bin rather than a dead zone inside max range.
                    if (BinaryPrimitives.ReadInt16BigEndian(
                        table.AsSpan(6 + (weapon.Value * 8 + (int)standOff) * 2)) <= 0)
                        standOff = bin + .5f;
                    var canHit = distance >= 0 && distance < 8 &&
                        BinaryPrimitives.ReadInt16BigEndian(
                            table.AsSpan(6 + (weapon.Value * 8 + (int)distance) * 2)) > 0;
                    var candidate = (weapon.Value, mask, range, standOff, canHit);
                    approachWeapon ??= candidate;
                    if (canHit)
                        return candidate;
                    break;
                }
        }
        return approachWeapon;
    }

    // E074's hash-bound 004F1180 model: pi/6 bias, six pi/3 sectors,
    // wire->render conversion done before obtaining bearing. Not an x87 boundary emulator.
    public static int Sector(float bearing, float heading)
    {
        var angle = (bearing - heading + MathF.PI / 6) % MathF.Tau;
        if (angle < 0) angle += MathF.Tau;
        return Math.Min(5, (int)(angle / (MathF.PI / 3)));
    }
    private static float Normalize(float angle) => MathF.IEEERemainder(angle, MathF.Tau);
    private static bool IsFinite(OriginalTacticalUnitShipRecord ship) =>
        float.IsFinite(ship.X) && float.IsFinite(ship.Y) && float.IsFinite(ship.Z) && float.IsFinite(ship.Direction);
    private static float Distance(OriginalTacticalUnitShipRecord a, OriginalTacticalUnitShipRecord b) =>
        MathF.Sqrt((a.X - b.X) * (a.X - b.X) + (a.Y - b.Y) * (a.Y - b.Y));
}
