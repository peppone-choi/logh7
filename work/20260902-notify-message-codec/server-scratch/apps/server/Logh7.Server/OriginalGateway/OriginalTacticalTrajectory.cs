namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalTacticalPose(OriginalTacticalVector Position, float Direction);
public readonly record struct OriginalTacticalSegment(int Kind, float Duration, OriginalTacticalVector Target);
public readonly record struct OriginalTacticalCursor(int Segment, float Elapsed, int AuthorizedSegment, int? PendingRoute);
public readonly record struct OriginalTacticalTrajectoryFrame(
    OriginalTacticalCursor Cursor, OriginalTacticalPose Desired, bool Completed);
public readonly record struct OriginalTacticalPursuitFrame(OriginalTacticalPose Actual, bool Moving);
public sealed record OriginalTacticalPath(OriginalTacticalPose Initial,
    IReadOnlyList<OriginalTacticalSegment> Segments, IReadOnlyList<OriginalTacticalVector> Waypoints);

public static class OriginalTacticalTrajectory
{
    // E057: 004C9D30's independent-path builder. actual corresponds to entity
    // +14/+24; pathOrigin to the stored +110 pose copied into trajectory +85C.
    // These may differ after delayed execution/correction; do not merge them.
    public static OriginalTacticalPath Build(byte mode, OriginalTacticalPose actual,
        OriginalTacticalPose pathOrigin, IReadOnlyList<OriginalTacticalPose> destinations,
        float speedPerTick, float turnRatePerTick)
    {
        ArgumentNullException.ThrowIfNull(destinations);
        RequireFinite(actual.Position, nameof(actual));
        RequireFinite(actual.Direction, nameof(actual));
        RequireFinite(pathOrigin.Position, nameof(pathOrigin));
        RequireFinite(pathOrigin.Direction, nameof(pathOrigin));
        RequireFinite(speedPerTick, nameof(speedPerTick));
        RequireFinite(turnRatePerTick, nameof(turnRatePerTick));
        if (speedPerTick < 0 || turnRatePerTick <= 0)
            throw new ArgumentOutOfRangeException(nameof(turnRatePerTick));
        var speed = speedPerTick * 24f;
        var turnRate = turnRatePerTick * 24f;
        RequireFinite(speed, nameof(speedPerTick));
        RequireFinite(turnRate, nameof(turnRatePerTick));
        foreach (var destination in destinations)
        {
            RequireFinite(destination.Position, nameof(destinations));
            RequireFinite(destination.Direction, nameof(destinations));
        }
        var segments = new List<OriginalTacticalSegment>();
        var heading = actual.Direction;
        var position = actual.Position;
        if (destinations.Count == 0 || mode is not (2 or 3 or 4))
        {
            AddTurn(pathOrigin.Direction);
            AddTranslation(pathOrigin.Position, 4);
        }
        else if (mode == 3)
        {
            AddTurn(destinations[0].Direction);
            AddTranslation(destinations[0].Position, 4);
        }
        else
        {
            foreach (var destination in destinations)
            {
                if (mode == 2)
                {
                    var leg = MeasureLeg(position, destination.Position, heading);
                    AddTurn(leg.Bearing);
                }
                AddTranslation(destination.Position, mode == 2 ? 2 : 4);
            }
            AddTurn(destinations[^1].Direction);
        }
        return new(pathOrigin, segments.ToArray(), destinations.Select(d => d.Position).ToArray());

        void AddTurn(float target)
        {
            var delta = target - heading;
            RequireFinite(delta, nameof(destinations));
            var duration = MathF.Abs(NormalizeAngle(delta)) / turnRate;
            RequireFinite(duration, nameof(turnRatePerTick));
            // Rotation targets are (0, heading, 0), not waypoint XYZ.
            segments.Add(new(3, duration, new(0, target, 0)));
            heading = target;
        }

        void AddTranslation(OriginalTacticalVector target, int kind)
        {
            var distance = MeasureLeg(position, target, heading).Distance;
            var duration = distance > 0 ? distance / speed : 0;
            RequireFinite(duration, nameof(speedPerTick));
            segments.Add(new(kind, duration, target));
            position = target;
        }
    }

    private static (float Distance, float Bearing) MeasureLeg(OriginalTacticalVector from,
        OriginalTacticalVector to, float fallbackHeading)
    {
        var dx = to.X - from.X;
        var dy = to.Y - from.Y;
        var dz = to.Z - from.Z;
        RequireFinite(dx, nameof(to));
        RequireFinite(dy, nameof(to));
        RequireFinite(dz, nameof(to));
        var distance = MathF.Sqrt(dx * dx + dy * dy + dz * dz);
        RequireFinite(distance, nameof(to));
        // 004CA160 does not write its bearing out-parameter at zero distance;
        // 004CA0D0 seeds it with the current heading before calling.
        if (distance <= 1e-6f) return (0, fallbackHeading);
        var sourceAngle = MathF.Acos(-dz / distance);
        var bearing = dx < 0 ? sourceAngle - MathF.PI : MathF.PI - sourceAngle;
        RequireFinite(bearing, nameof(to));
        return (distance, bearing);
    }

    // E056: 004CA2E0's post-target kinematics. The caller must resolve either
    // the independent trajectory or the leader-relative formation target.
    // deltaSeconds is the delta received by that function: ordinarily clock-
    // scaled, or (now-start)/24 on rebuild. worldTimeScale is the additional
    // +357EB4 rotation factor, not a multiplier to apply again to translation.
    // This intentionally preserves the original SIGNED heading comparison,
    // including negative-error snapping. It is not a symmetric turn clamp.
    public static OriginalTacticalPursuitFrame Pursue(OriginalTacticalPose actual,
        OriginalTacticalPose desired, float speedPerTick, float turnRatePerTick,
        float deltaSeconds, float worldTimeScale, bool routeActive, bool wasMoving)
    {
        RequireFinite(actual.Position, nameof(actual));
        RequireFinite(actual.Direction, nameof(actual));
        RequireFinite(desired.Position, nameof(desired));
        RequireFinite(desired.Direction, nameof(desired));
        RequireFinite(speedPerTick, nameof(speedPerTick));
        RequireFinite(turnRatePerTick, nameof(turnRatePerTick));
        RequireFinite(deltaSeconds, nameof(deltaSeconds));
        RequireFinite(worldTimeScale, nameof(worldTimeScale));
        if (speedPerTick < 0 || turnRatePerTick < 0 || deltaSeconds < 0 || worldTimeScale < 0)
            throw new ArgumentOutOfRangeException(nameof(deltaSeconds), "Kinematic inputs must be nonnegative.");
        var travel = speedPerTick * 24f * deltaSeconds;
        var turn = turnRatePerTick * 24f * deltaSeconds;
        RequireFinite(travel, nameof(speedPerTick));
        RequireFinite(turn, nameof(turnRatePerTick));
        var dx = desired.Position.X - actual.Position.X;
        var dy = desired.Position.Y - actual.Position.Y;
        var dz = desired.Position.Z - actual.Position.Z;
        RequireFinite(dx, nameof(desired));
        RequireFinite(dy, nameof(desired));
        RequireFinite(dz, nameof(desired));
        // 004CA160 -> 005DD880 includes all three coordinates.
        var distance = MathF.Sqrt(dx * dx + dy * dy + dz * dz);
        RequireFinite(distance, nameof(desired));
        if (distance <= 1e-6f) distance = 0;
        var stillPursuing = distance > travel;
        var position = desired.Position;
        if (stillPursuing)
        {
            // 004DC2B0 receives (actual-target)/XYZ-distance, then uses
            // asin(X)'s sign and acos(Z). This is NOT atan2 when Y differs.
            var sourceAngle = MathF.Acos(-dz / distance);
            var bearing = dx < 0 ? sourceAngle - MathF.PI : MathF.PI - sourceAngle;
            position = new(actual.Position.X + MathF.Sin(bearing) * travel,
                actual.Position.Y, actual.Position.Z + MathF.Cos(bearing) * travel);
        }
        var difference = desired.Direction - actual.Direction;
        RequireFinite(difference, nameof(desired));
        difference = NormalizeAngle(difference);
        var direction = desired.Direction;
        if (difference > turn)
        {
            direction = actual.Direction + turn * worldTimeScale;
            stillPursuing = true;
        }
        RequireFinite(position, nameof(desired));
        RequireFinite(direction, nameof(desired));
        // +0B is only cleared here, never raised. +0A protects an active route.
        return new(new(position, direction), wasMoving && (routeActive || stillPursuing));
    }

    // E043/E044: bounded port of 004CA620's desired-path cursor, not the
    // 004CA2E0 actual-entity pursuit/steering step. Do not publish Desired as
    // authoritative position until that step and shared battle ownership join.
    // PendingRoute is the INTERNAL entity+410 acknowledgement. A negative
    // 0423 wire message also rebuilds the path before this cursor sees it.
    public static OriginalTacticalTrajectoryFrame Step(OriginalTacticalPose initial,
        IReadOnlyList<OriginalTacticalSegment> segments, IReadOnlyList<OriginalTacticalVector> waypoints,
        OriginalTacticalCursor cursor, float deltaSeconds)
    {
        ArgumentNullException.ThrowIfNull(segments);
        ArgumentNullException.ThrowIfNull(waypoints);
        RequireFinite(deltaSeconds, nameof(deltaSeconds));
        RequireFinite(initial.Direction, nameof(initial));
        RequireFinite(initial.Position, nameof(initial));
        RequireFinite(cursor.Elapsed, nameof(cursor));
        if (cursor.Segment < 0 || cursor.Segment > segments.Count || cursor.Elapsed < 0)
            throw new ArgumentOutOfRangeException(nameof(cursor));
        foreach (var waypoint in waypoints) RequireFinite(waypoint, nameof(waypoints));
        var position = initial.Position;
        var direction = initial.Direction;
        for (var i = 0; i < segments.Count; i++)
        {
            if (segments[i].Kind is not (2 or 3 or 4))
                throw new ArgumentOutOfRangeException(nameof(segments));
            RequireFinite(segments[i].Duration, nameof(segments));
            RequireFinite(segments[i].Target, nameof(segments));
            if (segments[i].Duration < 0) throw new ArgumentOutOfRangeException(nameof(segments));
            if (i < cursor.Segment)
            {
                if (segments[i].Kind == 3) direction = segments[i].Target.Y;
                else position = segments[i].Target;
            }
        }
        var desired = new OriginalTacticalPose(position, direction);
        if (deltaSeconds <= 0)
            return new(cursor, desired, cursor.Segment >= segments.Count);

        var remaining = deltaSeconds;
        do
        {
            var consumedAcknowledgment = cursor.PendingRoute.HasValue;
            if (cursor.PendingRoute is int route)
            {
                var authorized = cursor.AuthorizedSegment;
                if (unchecked((uint)route) >= (uint)waypoints.Count)
                    authorized = segments.Count - 1;
                else
                {
                    var waypoint = waypoints[route];
                    for (var i = 0; i < segments.Count; i++)
                    {
                        var target = segments[i].Target;
                        if (target.X != waypoint.X || target.Z != waypoint.Z) continue;
                        authorized = i;
                        break;
                    }
                }
                cursor = cursor with { AuthorizedSegment = authorized, PendingRoute = null };
            }
            if (cursor.Segment >= segments.Count) break;
            var segment = segments[cursor.Segment];
            var nextElapsed = remaining + cursor.Elapsed;
            RequireFinite(nextElapsed, nameof(deltaSeconds));
            var fraction = segment.Duration > 0 ? nextElapsed / segment.Duration : 1;
            RequireFinite(fraction, nameof(deltaSeconds));
            // Deliberately before the elapsed-authorization guard, as in the
            // original. This may extrapolate: it is a desired point, not a
            // claim that the actual ship has reached or passed that point.
            desired = segment.Kind == 3
                ? new(position, direction + NormalizeAngle(segment.Target.Y - direction) * fraction)
                : new(new(
                    position.X + (segment.Target.X - position.X) * fraction,
                    position.Y + (segment.Target.Y - position.Y) * fraction,
                    position.Z + (segment.Target.Z - position.Z) * fraction), direction);
            RequireFinite(desired.Position, nameof(segments));
            RequireFinite(desired.Direction, nameof(segments));
            if (cursor.Segment <= cursor.AuthorizedSegment)
                cursor = cursor with { Elapsed = nextElapsed };
            if (cursor.Elapsed <= segment.Duration) break;

            remaining = cursor.Elapsed - segment.Duration;
            if (segment.Kind == 3) direction = segment.Target.Y;
            else position = segment.Target;
            desired = new(position, direction);
            cursor = cursor with { Segment = cursor.Segment + 1, Elapsed = 0 };
            if (consumedAcknowledgment) break;
        } while (remaining > 0);
        return new(cursor, desired, cursor.Segment >= segments.Count);
    }

    private static float NormalizeAngle(float angle)
    {
        // 004DC320: double epsilon1e-6, single-precision PI/tau constants.
        // This managed calculation is not claimed to emulate x87 bit-for-bit.
        if (Math.Abs((double)angle) < 1e-6) return 0;
        if (Math.Abs(angle) >= MathF.PI)
        {
            angle -= MathF.Truncate(angle / MathF.Tau) * MathF.Tau;
            if (angle > MathF.PI) angle -= MathF.Tau;
            else if (angle < -MathF.PI) angle += MathF.Tau;
        }
        return angle;
    }

    private static void RequireFinite(float value, string name)
    {
        if (!float.IsFinite(value)) throw new ArgumentOutOfRangeException(name);
    }

    private static void RequireFinite(OriginalTacticalVector vector, string name)
    {
        RequireFinite(vector.X, name);
        RequireFinite(vector.Y, name);
        RequireFinite(vector.Z, name);
    }
}
