using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalTacticalPursuitTests
{
    private static readonly OriginalTacticalPose Origin = new(new(0, 7, 0), 0);

    [Fact]
    public void CapsTravelForLevelTarget()
    {
        var frame = Pursue(new(new(30, 7, 40), 0), speed: 1, dt: .5f);
        Assert.InRange(frame.Actual.Position.X, 7.19999f, 7.20001f);
        Assert.InRange(frame.Actual.Position.Z, 9.59999f, 9.60001f);
        Assert.Equal(7, frame.Actual.Position.Y);
        Assert.True(frame.Moving);
    }

    [Theory]
    [InlineData(.5f)]
    [InlineData(1f)]
    public void ExactOrExcessTravelCopiesAllCoordinatesAndClearsInactiveMovement(float dt)
    {
        var desired = new OriginalTacticalPose(new(0, 7, 12), 0);
        var frame = Pursue(desired, speed: 1, dt: dt);
        Assert.Equal(desired, frame.Actual);
        Assert.False(frame.Moving);
    }

    [Fact]
    public void VerticalSeparationDoesNotCountAsArrivedAtZeroSpeed()
    {
        var frame = Pursue(new(new(0, 99, 0), 0), speed: 0);
        Assert.Equal(7, frame.Actual.Position.Y);
        Assert.True(frame.Moving);
    }

    [Fact]
    public void ThreeDimensionalDistanceFeedsBearingButPartialMotionKeepsHeight()
    {
        // Original bearing uses acos(-dz / XYZ-distance), not atan2(dx, dz).
        // XYZ distance=15, travel=12, cos(bearing)=12/15=.8.
        var frame = Pursue(new(new(0, 16, 12), 0), speed: 1, dt: .5f);
        Assert.InRange(frame.Actual.Position.X, 7.19999f, 7.20001f);
        Assert.InRange(frame.Actual.Position.Z, 9.59999f, 9.60001f);
        Assert.Equal(7, frame.Actual.Position.Y);
        Assert.True(frame.Moving);
    }

    [Fact]
    public void ExactThreeDimensionalDistanceCopiesHeight()
    {
        var desired = new OriginalTacticalPose(new(0, 16, 12), 0);
        var frame = Pursue(desired, speed: 1, dt: .625f);
        Assert.Equal(desired, frame.Actual);
        Assert.False(frame.Moving);
    }

    [Fact]
    public void NegativeXTargetSelectsNegativeBearing()
    {
        var frame = Pursue(new(new(-30, 7, 40), 0), speed: 1, dt: .5f);
        Assert.InRange(frame.Actual.Position.X, -7.20001f, -7.19999f);
        Assert.InRange(frame.Actual.Position.Z, 9.59999f, 9.60001f);
    }

    [Fact]
    public void ActiveRoutePreservesMovingFlagAtDesiredPoint()
    {
        var frame = Pursue(new(new(0, 7, 12), 0), speed: 1, dt: 1, routeActive: true);
        Assert.Equal(12, frame.Actual.Position.Z);
        Assert.True(frame.Moving);
    }

    [Fact]
    public void PursuitDoesNotSetPreviouslyClearedMovingFlag()
    {
        var frame = Pursue(new(new(0, 7, 100), 0), speed: 1, dt: .5f, wasMoving: false);
        Assert.Equal(12, frame.Actual.Position.Z);
        Assert.False(frame.Moving);
    }

    [Fact]
    public void PositiveHeadingErrorAboveLimitUsesAdditionalWorldScale()
    {
        var frame = Pursue(new(Origin.Position, 2), turn: .125f, dt: .5f, scale: .5f);
        Assert.Equal(.75f, frame.Actual.Direction);
        Assert.True(frame.Moving);
    }

    [Fact]
    public void NegativeHeadingErrorSnapsBecauseOriginalComparisonIsSigned()
    {
        var frame = Pursue(new(Origin.Position, -2), turn: .125f, dt: .5f, scale: .5f);
        Assert.Equal(-2, frame.Actual.Direction);
        Assert.False(frame.Moving);
    }

    [Fact]
    public void ExactPositiveHeadingLimitSnapsWithoutExtraWorldScale()
    {
        var frame = Pursue(new(Origin.Position, 1.5f), turn: .125f, dt: .5f, scale: .5f);
        Assert.Equal(1.5f, frame.Actual.Direction);
        Assert.False(frame.Moving);
    }

    [Fact]
    public void WrappedPositiveDifferenceTurnsAcrossTauWithoutNormalizingResult()
    {
        var frame = OriginalTacticalTrajectory.Pursue(new(Origin.Position, 6),
            new(Origin.Position, .5f), 0, .125f, .125f, 1, false, true);
        Assert.Equal(6.375f, frame.Actual.Direction);
        Assert.True(frame.Moving);
    }

    [Fact]
    public void ZeroDeltaStillSnapsNegativeHeadingAsOriginalDoes()
    {
        var frame = Pursue(new(Origin.Position, -2), dt: 0);
        Assert.Equal(-2, frame.Actual.Direction);
        Assert.False(frame.Moving);
    }

    [Fact]
    public void DesiredTrajectoryDoesNotBypassActualSpeedCap()
    {
        var planned = OriginalTacticalTrajectory.Step(Origin,
            [new(2, 1, new(0, 7, 96))], [new(0, 7, 96)], new(0, 0, 0, null), .5f);
        var frame = Pursue(planned.Desired, speed: 1, dt: .5f);
        Assert.Equal(48, planned.Desired.Position.Z);
        Assert.Equal(12, frame.Actual.Position.Z);
    }

    [Theory]
    [InlineData(float.NaN, 1, 1, 1)]
    [InlineData(1, float.PositiveInfinity, 1, 1)]
    [InlineData(1, 1, float.NegativeInfinity, 1)]
    [InlineData(1, 1, 1, float.NaN)]
    [InlineData(-1, 1, 1, 1)]
    [InlineData(1, -1, 1, 1)]
    [InlineData(1, 1, -1, 1)]
    [InlineData(1, 1, 1, -1)]
    public void InvalidKinematicInputsAreRejected(float speed, float turn, float dt, float scale)
        => Assert.Throws<ArgumentOutOfRangeException>(() => Pursue(Origin, speed, turn, dt, scale));

    [Fact]
    public void NonfinitePoseIsRejected()
        => Assert.Throws<ArgumentOutOfRangeException>(() => Pursue(new(new(float.NaN, 0, 0), 0)));

    [Fact]
    public void FiniteInputOverflowIsRejected()
        => Assert.Throws<ArgumentOutOfRangeException>(() => Pursue(Origin, speed: float.MaxValue));

    private static OriginalTacticalPursuitFrame Pursue(OriginalTacticalPose desired, float speed = 0,
        float turn = 0, float dt = 1, float scale = 1, bool routeActive = false, bool wasMoving = true)
        => OriginalTacticalTrajectory.Pursue(Origin, desired, speed, turn, dt, scale, routeActive, wasMoving);
}
