using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalCharacterCodecTests
{
    [Fact]
    public void Authored_military_sides_use_the_original_constmsg_power_codes()
    {
        Assert.Equal(3, OriginalFaction.OpposingMilitaryPower(2));
        Assert.Equal(2, OriginalFaction.OpposingMilitaryPower(3));
        Assert.All(OriginalLotteryCandidateCatalog.Templates, row => Assert.Equal(3, row.Faction));
    }

    [Fact]
    public void Creation_tail_keeps_special_count_title_rank_and_flagship_fields_separate()
    {
        // Independent wire vector from original input_from_stream 0x004066F0.
        // Expanded offsets +58/+59/+5A/+5B/+5C are five consecutive wire bytes;
        // +5E is one big-endian u16, followed by the flagship pstr and check.
        var payload = Convert.FromHexString(
            "1008000000002A01020102004100000200420000" +
            "000000120304000000050102030405060708" +
            "090A0B0C0D12340200580000EE");

        var parsed = OriginalCharacterCodec.DecodeCreate(payload);

        Assert.True(parsed.Success, parsed.ErrorCode);
        var command = parsed.Command!.Value;
        Assert.Equal(9, command.BonusPoint);
        Assert.Equal(10, command.SpecialAbilityCount);
        Assert.Equal(11, command.Title);
        Assert.Equal(12, command.Rank);
        Assert.Equal(13, command.FlagshipType);
        Assert.Equal(0x1234, command.FlagshipKind);
        Assert.Equal("X", command.FlagshipName);
        Assert.Equal(0xEE, command.Check);
        Assert.Equal(payload, OriginalCharacterCodec.EncodeAccepted(command)[4..]);
    }
}
