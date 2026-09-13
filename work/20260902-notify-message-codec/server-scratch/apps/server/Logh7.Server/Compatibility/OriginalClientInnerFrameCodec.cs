using System.Buffers.Binary;

namespace Logh7.Server.Compatibility;

public enum OriginalClientInnerFrameStatus
{
    Success,
    Invalid
}

public readonly record struct OriginalClientInnerFrameDecodeResult(
    OriginalClientInnerFrameStatus Status,
    uint Sequence,
    byte[]? Payload,
    string? ErrorCode);

public static class OriginalClientInnerFrameCodec
{
    private const int ChecksumSize = sizeof(ushort);
    private const int SequenceSize = sizeof(uint);
    private const int LengthSize = sizeof(ushort);
    private const int HeaderSize = ChecksumSize + SequenceSize + LengthSize;

    public static byte[] Encode(
        ReadOnlySpan<byte> payload,
        ReadOnlySpan<byte> directionalKey,
        uint sequence)
    {
        ValidateDirectionalKey(directionalKey);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(payload.Length, ushort.MaxValue);

        var plaintext = new byte[HeaderSize + payload.Length];
        BinaryPrimitives.WriteUInt32BigEndian(plaintext.AsSpan(ChecksumSize), sequence);
        BinaryPrimitives.WriteUInt16BigEndian(
            plaintext.AsSpan(ChecksumSize + SequenceSize),
            (ushort)payload.Length);
        payload.CopyTo(plaintext.AsSpan(HeaderSize));
        BinaryPrimitives.WriteUInt16BigEndian(
            plaintext,
            OriginalClientWireChecksum.Compute(plaintext.AsSpan(ChecksumSize)));

        return new OriginalClientBlowfish(directionalKey).EncryptPadded(plaintext);
    }

    public static OriginalClientInnerFrameDecodeResult Decode(
        ReadOnlySpan<byte> ciphertext,
        ReadOnlySpan<byte> directionalKey,
        uint previousSequence)
    {
        ValidateDirectionalKey(directionalKey);
        if (ciphertext.Length < OriginalClientBlowfish.BlockSize ||
            (ciphertext.Length & (OriginalClientBlowfish.BlockSize - 1)) != 0)
        {
            return Invalid("original.inner.cipher-length");
        }

        var plaintext = new OriginalClientBlowfish(directionalKey).DecryptBlocks(ciphertext);
        if (plaintext.Length < HeaderSize)
        {
            return Invalid("original.inner.plain-length");
        }

        var sequence = BinaryPrimitives.ReadUInt32BigEndian(plaintext.AsSpan(ChecksumSize));
        var payloadLength = BinaryPrimitives.ReadUInt16BigEndian(
            plaintext.AsSpan(ChecksumSize + SequenceSize));
        if (payloadLength > plaintext.Length - HeaderSize)
        {
            return Invalid("original.inner.payload-length");
        }

        var meaningfulLength = HeaderSize + payloadLength;
        var expectedChecksum = BinaryPrimitives.ReadUInt16BigEndian(plaintext);
        var actualChecksum = OriginalClientWireChecksum.Compute(
            plaintext.AsSpan(ChecksumSize, meaningfulLength - ChecksumSize));
        if (expectedChecksum != actualChecksum)
        {
            return Invalid("original.inner.checksum");
        }

        if (sequence <= previousSequence)
        {
            return Invalid("original.inner.sequence");
        }

        return new OriginalClientInnerFrameDecodeResult(
            OriginalClientInnerFrameStatus.Success,
            sequence,
            plaintext.AsSpan(HeaderSize, payloadLength).ToArray(),
            null);
    }

    private static void ValidateDirectionalKey(ReadOnlySpan<byte> directionalKey)
    {
        if (directionalKey.Length != OriginalClientCipherHandshake.SessionKeyLength)
        {
            throw new ArgumentException(
                $"The original client requires a {OriginalClientCipherHandshake.SessionKeyLength}-byte directional key.",
                nameof(directionalKey));
        }
    }

    private static OriginalClientInnerFrameDecodeResult Invalid(string errorCode) =>
        new(OriginalClientInnerFrameStatus.Invalid, 0, null, errorCode);
}

