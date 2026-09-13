using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalTacticalTrajectoryTests
{
    private static readonly OriginalTacticalPose Origin = new(new(0, 7, 0), 0);
    private static readonly OriginalTacticalVector End = new(24, 7, 0);
    private static readonly OriginalTacticalSegment[] Move = [new(2, 1, End)];
    private static readonly OriginalTacticalVector[] Waypoints = [End];

    [Fact]
    public void TranslationProducesIntermediatePositionInsteadOfImmediateDestination()
    {
        var frame = Step(new(0, 0, 0, null), .25f);
        Assert.Equal(new OriginalTacticalVector(6, 7, 0), frame.Desired.Position);
        Assert.Equal(.25f, frame.Cursor.Elapsed);
        Assert.False(frame.Completed);
    }

    [Fact]
    public void ExactDurationDoesNotAdvanceUntilTimeStrictlyExceedsIt()
    {
        var exact = Step(new(0, .75f, 0, null), .25f);
        Assert.Equal(End, exact.Desired.Position);
        Assert.Equal(0, exact.Cursor.Segment);
        Assert.False(exact.Completed);
        var after = Step(exact.Cursor, .125f);
        Assert.True(after.Completed);
        Assert.Equal(1, after.Cursor.Segment);
        Assert.Equal(End, after.Desired.Position);
    }

    [Fact]
    public void UnauthorizedSegmentStillCalculatesDesiredPoseWithoutAccumulatingTime()
    {
        var frame = Step(new(0, .25f, -1, null), .25f);
        Assert.Equal(new OriginalTacticalVector(12, 7, 0), frame.Desired.Position);
        Assert.Equal(.25f, frame.Cursor.Elapsed);
        Assert.Equal(0, frame.Cursor.Segment);
    }

    [Fact]
    public void RouteMatchesFirstTargetUsingXzOnlyEvenWhenThatTargetIsRotation()
    {
        OriginalTacticalSegment[] segments = [new(3, .5f, new(24, 1, 0)), new(2, 1, End)];
        var frame = OriginalTacticalTrajectory.Step(Origin, segments, [new(24, 999, 0)],
            new(0, 0, -1, 0), .25f);
        Assert.Equal(0, frame.Cursor.AuthorizedSegment);
        Assert.Equal(.5f, frame.Desired.Direction);
        Assert.Equal(.25f, frame.Cursor.Elapsed);
        Assert.Null(frame.Cursor.PendingRoute);
    }

    [Fact]
    public void MissingRouteTargetPreservesPreviousAuthorization()
    {
        var frame = OriginalTacticalTrajectory.Step(Origin, Move, [new(99, 0, 99)],
            new(0, 0, -1, 0), .25f);
        Assert.Equal(-1, frame.Cursor.AuthorizedSegment);
        Assert.Equal(0, frame.Cursor.Elapsed);
        Assert.Null(frame.Cursor.PendingRoute);
    }

    [Theory]
    [InlineData(-1)]
    [InlineData(-2)]
    [InlineData(1)]
    public void OutsideWaypointRangeAuthorizesRemainingSegments(int route)
    {
        var frame = Step(new(0, 0, -1, route), .25f);
        Assert.Equal(0, frame.Cursor.AuthorizedSegment);
        Assert.Equal(.25f, frame.Cursor.Elapsed);
    }

    [Fact]
    public void ConsumingAcknowledgmentStopsRemainderLoopAfterOneSegment()
    {
        OriginalTacticalSegment[] segments = [new(3, .5f, new(0, 1, 0)), new(2, 1, End)];
        var frame = OriginalTacticalTrajectory.Step(Origin, segments, Waypoints,
            new(0, 0, -1, -1), 1);
        Assert.Equal(1, frame.Cursor.Segment);
        Assert.Equal(0, frame.Cursor.Elapsed);
        Assert.Equal(Origin.Position, frame.Desired.Position);
        Assert.Equal(1, frame.Desired.Direction);
        var next = OriginalTacticalTrajectory.Step(Origin, segments, Waypoints, frame.Cursor, .25f);
        Assert.Equal(new OriginalTacticalVector(6, 7, 0), next.Desired.Position);
    }

    [Fact]
    public void WithoutNewAcknowledgmentRemainderAdvancesIntoNextSegment()
    {
        OriginalTacticalSegment[] segments = [new(3, .5f, new(0, 1, 0)), new(2, 1, End)];
        var frame = OriginalTacticalTrajectory.Step(Origin, segments, Waypoints,
            new(0, 0, 1, null), .75f);
        Assert.Equal(1, frame.Cursor.Segment);
        Assert.Equal(.25f, frame.Cursor.Elapsed);
        Assert.Equal(new OriginalTacticalVector(6, 7, 0), frame.Desired.Position);
        Assert.Equal(1, frame.Desired.Direction);
    }

    [Theory]
    [InlineData(2)]
    [InlineData(4)]
    public void BothTranslationSegmentTypesPreserveInterpolatedY(int kind)
    {
        var frame = OriginalTacticalTrajectory.Step(Origin, [new(kind, 1, new(24, 11, 0))],
            Waypoints, new(0, 0, 0, null), .5f);
        Assert.Equal(new OriginalTacticalVector(12, 9, 0), frame.Desired.Position);
    }

    [Fact]
    public void RotationTakesShortArcAcrossFullTurnBoundary()
    {
        var frame = OriginalTacticalTrajectory.Step(new(new(0, 7, 0), 6),
            [new(3, 1, new(0, .2f, 0))], Waypoints, new(0, 0, 0, null), .5f);
        Assert.InRange(frame.Desired.Direction, 6.24159f, 6.24160f);
        Assert.Equal(Origin.Position, frame.Desired.Position);
    }

    [Fact]
    public void ZeroDeltaDoesNotConsumePendingRouteOrPartialElapsed()
    {
        var cursor = new OriginalTacticalCursor(0, .25f, -1, 0);
        var frame = Step(cursor, 0);
        Assert.Equal(cursor, frame.Cursor);
        Assert.Equal(Origin, frame.Desired);
    }

    [Fact]
    public void ZeroDurationCompletesWithoutDivisionByZero()
    {
        var frame = OriginalTacticalTrajectory.Step(Origin, [new(2, 0, End)], Waypoints,
            new(0, 0, 0, null), .125f);
        Assert.True(frame.Completed);
        Assert.Equal(End, frame.Desired.Position);
    }

    [Fact]
    public void CompletedSegmentsSupplyPositionAndHeadingOnSubsequentCalls()
    {
        OriginalTacticalSegment[] segments = [new(2, 1, End), new(3, 1, new(0, 2, 0))];
        var frame = OriginalTacticalTrajectory.Step(Origin, segments, Waypoints,
            new(2, 0, 1, null), 0);
        Assert.Equal(new OriginalTacticalPose(End, 2), frame.Desired);
        Assert.True(frame.Completed);
    }

    [Theory]
    [InlineData(float.NaN)]
    [InlineData(float.PositiveInfinity)]
    [InlineData(float.NegativeInfinity)]
    public void NonfiniteDeltaCannotContaminateTrajectory(float delta) =>
        Assert.Throws<ArgumentOutOfRangeException>(() => Step(new(0, 0, 0, null), delta));

    [Theory]
    [InlineData(-1)]
    [InlineData(1)]
    [InlineData(5)]
    public void UnknownSegmentTypesAreRejectedAtInternalBoundary(int kind) =>
        Assert.Throws<ArgumentOutOfRangeException>(() => OriginalTacticalTrajectory.Step(
            Origin, [new(kind, 1, End)], Waypoints, new(0, 0, 0, null), .25f));

    private static OriginalTacticalTrajectoryFrame Step(OriginalTacticalCursor cursor, float delta) =>
        OriginalTacticalTrajectory.Step(Origin, Move, Waypoints, cursor, delta);

    [Theory]
    [InlineData(-1)]
    [InlineData(float.NaN)]
    [InlineData(float.PositiveInfinity)]
    public void InvalidDurationCannotEnterCursor(float duration) =>
        Assert.Throws<ArgumentOutOfRangeException>(() => OriginalTacticalTrajectory.Step(
            Origin, [new(2, duration, End)], Waypoints, new(0, 0, 0, null), .25f));

    [Theory]
    [InlineData(-1, 0)]
    [InlineData(2, 0)]
    [InlineData(0, -1)]
    [InlineData(0, float.NaN)]
    public void InvalidCursorCannotSkipOrIndexOutsidePath(int index, float elapsed) =>
        Assert.Throws<ArgumentOutOfRangeException>(() => Step(new(index, elapsed, 0, null), .25f));

    [Fact]
    public void NonfiniteTargetCannotProduceInvalidDesiredPose() =>
        Assert.Throws<ArgumentOutOfRangeException>(() => OriginalTacticalTrajectory.Step(
            Origin, [new(2, 1, new(float.NaN, 7, 0))], Waypoints, new(0, 0, 0, null), .25f));

    [Fact]
    public void NonfiniteInitialHeadingCannotProduceInvalidDesiredPose() =>
        Assert.Throws<ArgumentOutOfRangeException>(() => OriginalTacticalTrajectory.Step(
            new(Origin.Position, float.PositiveInfinity), Move, Waypoints, new(0, 0, 0, null), .25f));

    [Fact]
    public void NonfiniteWaypointIsRejectedRatherThanSilentlyFailingRouteLookup() =>
        Assert.Throws<ArgumentOutOfRangeException>(() => OriginalTacticalTrajectory.Step(
            Origin, Move, [new(float.NaN, 0, 0)], new(0, 0, -1, 0), .25f));

    [Fact]
    public void FiniteInputsWhoseTimeSumOverflowsAreRejected() =>
        Assert.Throws<ArgumentOutOfRangeException>(() => Step(new(0, float.MaxValue, 0, null), float.MaxValue));
}
