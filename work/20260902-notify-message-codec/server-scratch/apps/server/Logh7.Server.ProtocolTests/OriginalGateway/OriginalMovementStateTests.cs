using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalMovementStateTests
{
    [Fact]
    public void Unit_information_projects_authored_cruising_before_and_after_warp()
    {
        var before = OriginalWorldEntryCodec.EncodeUnit(
            2,
            OriginalMoveGridAuthority.MinimalWorldSourceCellId);
        var after = OriginalWorldEntryCodec.EncodeUnit(
            2,
            OriginalMoveGridAuthority.MinimalWorldDestinationCellId);

        Assert.Equal(
            BitConverter.SingleToUInt32Bits(10.0f),
            ReadTrailingUInt32(before));
        Assert.Equal(
            BitConverter.SingleToUInt32Bits(9.0f),
            ReadTrailingUInt32(after));
    }

    [Fact]
    public void Strategic_warp_consumes_one_unit_of_authored_cruising()
    {
        var state = OriginalMoveGridAuthority.CreateNewDesignMinimalWorld();

        var decision = OriginalMoveGridAuthority.Transition(
            state,
            new OriginalMoveGridAuthorityCommand(
                state.UnitId,
                state.AuthorityCardId,
                state.CellId,
                OriginalMoveGridAuthority.MinimalWorldDestinationCellId,
                OriginalMoveGridAuthority.MinimalWorldWarpAction));

        Assert.Equal(OriginalMoveGridAuthorityStatus.Allowed, decision.Status);
        var notification = Assert.NotNull(decision.Notification);
        var movement = Assert.Single(notification.Records);
        Assert.Equal(state.UnitId, movement.Unit);
        Assert.Equal(BitConverter.SingleToUInt32Bits(9.0f), movement.Cruising);
    }

    private static uint ReadTrailingUInt32(byte[] frame) =>
        System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(frame[^4..]);
}
