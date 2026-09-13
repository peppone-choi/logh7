using System.Buffers.Binary;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalInstitutionContentTests
{
    [Theory]
    [InlineData(101, 1)]
    [InlineData(102, 2)]
    public void Shipped_bases_have_nonempty_dock_and_residential_locations(int grid, uint baseId)
    {
        var catalog = OriginalBattlefieldCatalog.LoadDefault();
        var frame = catalog.EncodeInstitutionFrame((uint)grid);
        Assert.Equal((byte)1, frame[6]);
        Assert.Equal(baseId, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(7)));
        Assert.Equal((byte)2, frame[11]);
        // Independent packed parser: no empty record shortcut for the departure gate.
        var cursor = 12;
        var kinds = new List<ushort>();
        var ids = new HashSet<uint>();
        for (int i = 0; i < frame[11]; i++)
        {
            var kind = BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(cursor));
            kinds.Add(kind);
            Assert.NotEqual(0u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(cursor+2)));
            Assert.Equal((byte)1, frame[cursor+6]);
            cursor += 7;
            Assert.Equal(kind == 4 ? (ushort)6 : (ushort)13,
                BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(cursor)));
            Assert.True(ids.Add(BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(cursor+2))));
            Assert.Equal(kind == 4 ? (ushort)7 : (ushort)1,
                BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(cursor+6)));
            cursor += 8;
        }
        Assert.Equal(new ushort[] {4,16}, kinds);
        Assert.Equal(frame.Length, cursor);
        Assert.Equal("NEW_DESIGN", catalog.Resolve((uint)grid).EvidenceStatus);
    }

    [Fact]
    public void An_uninhabited_grid_clears_facilities_instead_of_leaking_the_fallback_base()
    {
        Assert.Equal("00000000032100", Convert.ToHexString(OriginalBattlefieldCatalog.LoadDefault().EncodeInstitutionFrame(103)));
    }
}
