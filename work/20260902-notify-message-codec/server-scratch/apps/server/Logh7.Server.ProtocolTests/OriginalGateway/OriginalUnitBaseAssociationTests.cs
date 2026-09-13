using System.Buffers.Binary;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalUnitBaseAssociationTests
{
    [Theory]
    [InlineData(0u)]
    [InlineData(7u)]
    [InlineData(0xffffffffu)]
    public void Explicit_restored_association_is_not_replaced_by_Base1(uint baseId)
    {
        var restored = new OriginalGridUnitRecord(20,20,39,101,4,baseId);
        var wire=OriginalWorldEntryCodec.EncodeUnit(restored.UnitId,restored.CurrentCellId,restored.BaseId);
        Assert.Equal(baseId,BinaryPrimitives.ReadUInt32BigEndian(wire.AsSpan(28,4)));
    }

    [Fact]
    public void Legacy_destination_projection_does_not_redock_at_the_starting_base()
    {
        var wire=OriginalWorldEntryCodec.EncodeUnit(2,102);
        Assert.Equal(0u,BinaryPrimitives.ReadUInt32BigEndian(wire.AsSpan(28,4)));
    }

    [Fact]
    public void Strategic_warp_clears_association_in_state_and_notification()
    {
        var before=new OriginalMoveGridAuthorityState(2,39,101,7);
        var result=OriginalMoveGridAuthority.Transition(before,new(2,39,101,102,0x2b));
        Assert.Equal(OriginalMoveGridAuthorityStatus.Allowed,result.Status);
        Assert.Equal(0u,result.State.BaseId);
        Assert.Equal(0u,Assert.NotNull(result.Notification).Base);
    }

    [Fact]
    public void Rejected_warp_keeps_existing_association()
    {
        var before=new OriginalMoveGridAuthorityState(2,39,101,7);
        var result=OriginalMoveGridAuthority.Transition(before,new(2,39,101,999,0x2b));
        Assert.Equal(OriginalMoveGridAuthorityStatus.Rejected,result.Status);
        Assert.Equal(before,result.State);
    }
}
