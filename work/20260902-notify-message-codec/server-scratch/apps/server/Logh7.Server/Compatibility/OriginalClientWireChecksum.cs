using System.Buffers.Binary;

namespace Logh7.Server.Compatibility;

internal static class OriginalClientWireChecksum
{
    public static ushort Compute(ReadOnlySpan<byte> bytes)
    {
        uint accumulator = 0;
        var dwordLength = bytes.Length & ~(sizeof(uint) - 1);
        for (var offset = 0; offset < dwordLength; offset += sizeof(uint))
        {
            accumulator ^= BinaryPrimitives.ReadUInt32LittleEndian(bytes[offset..]);
        }

        for (var offset = dwordLength; offset < bytes.Length; offset++)
        {
            accumulator ^= bytes[offset];
        }

        return (ushort)((accumulator >> 16) ^ accumulator);
    }
}

