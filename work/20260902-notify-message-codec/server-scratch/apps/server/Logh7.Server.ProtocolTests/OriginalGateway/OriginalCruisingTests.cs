using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalCruisingTests
{
    [Fact]
    public void Return_route_and_repeated_round_trips_consume_remaining_cruising_until_exhaustion()
    {
        var state=new OriginalMoveGridAuthorityState(2,39,102,2,3);
        foreach(var destination in new uint[]{101,102,101})
        {
            var expected=state.Cruising-1;
            var result=OriginalMoveGridAuthority.Transition(state,new(2,39,state.CellId,destination,0x2b));
            Assert.Equal(OriginalMoveGridAuthorityStatus.Allowed,result.Status);
            Assert.Equal(expected,result.State.Cruising);
            Assert.Equal(0u,result.State.BaseId);
            Assert.Equal(BitConverter.SingleToUInt32Bits(expected),Assert.Single(Assert.NotNull(result.Notification).Records).Cruising);
            state=result.State;
        }
        var exhausted=OriginalMoveGridAuthority.Transition(state,new(2,39,101,102,0x2b));
        Assert.Equal(OriginalMoveGridAuthorityStatus.Rejected,exhausted.Status);
        Assert.Equal("MOVE_GRID_CRUISING_EXHAUSTED",exhausted.ErrorCode);
        Assert.Equal(state,exhausted.State);
    }

    [Theory]
    [InlineData(float.NaN)]
    [InlineData(float.PositiveInfinity)]
    [InlineData(-1f)]
    public void Invalid_cruising_never_produces_a_warp(float cruising)
    {
        var before=new OriginalMoveGridAuthorityState(2,39,101,1,cruising);
        var result=OriginalMoveGridAuthority.Transition(before,new(2,39,101,102,0x2b));
        Assert.Equal(OriginalMoveGridAuthorityStatus.Rejected,result.Status);
        Assert.Null(result.Notification);
        Assert.Equal(before,result.State);
    }
}
