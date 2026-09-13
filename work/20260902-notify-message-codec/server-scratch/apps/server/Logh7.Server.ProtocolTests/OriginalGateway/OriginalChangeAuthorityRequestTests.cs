using System.Buffers.Binary;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalChangeAuthorityRequestTests
{
    [Fact]
    public void Reads_target_after_variable_unit_array()
    {
        var payload = Convert.FromHexString("0420010203041122334455667788021020304090ABCDEF76543210");
        Assert.True(OriginalTacticalCommandCodec.TryDecodeChangeAuthorityCommand(payload, out var command));
        Assert.Equal(0x01020304u, command.Time);
        Assert.Equal(0x11223344u, command.Wait);
        Assert.Equal(0x55667788u, command.RequestId);
        Assert.Equal(new uint[] { 0x10203040, 0x90abcdef }, command.UnitIds);
        Assert.Equal(0x76543210u, command.TargetId);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(32)]
    public void Accepts_original_wire_count_boundaries(int count)
    {
        var payload = new byte[19 + count * 4];
        payload[0] = 4;
        payload[1] = 0x20;
        payload[14] = (byte)count;
        BinaryPrimitives.WriteUInt32BigEndian(payload.AsSpan(payload.Length - 4), 0x12345678);
        Assert.True(OriginalTacticalCommandCodec.TryDecodeChangeAuthorityCommand(payload, out var command));
        Assert.Equal(count, command.UnitIds.Count);
        Assert.Equal(0x12345678u, command.TargetId);
    }

    [Fact]
    public void Rejects_truncation_trailing_bytes_wrong_type_and_overflow()
    {
        var valid = Convert.FromHexString("0420000000010000000200000003010000000400000005");
        for (var length = 0; length < valid.Length; length++)
            Assert.False(OriginalTacticalCommandCodec.TryDecodeChangeAuthorityCommand(valid.AsSpan(0, length), out _));
        Assert.False(OriginalTacticalCommandCodec.TryDecodeChangeAuthorityCommand([.. valid, 0], out _));
        valid[1] = 0x39;
        Assert.False(OriginalTacticalCommandCodec.TryDecodeChangeAuthorityCommand(valid, out _));
        var over = new byte[19 + 33 * 4];
        over[0] = 4; over[1] = 0x20; over[14] = 33;
        Assert.False(OriginalTacticalCommandCodec.TryDecodeChangeAuthorityCommand(over, out _));
    }
}
