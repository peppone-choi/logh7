using System.Buffers.Binary;
using System.Security.Cryptography;

namespace Logh7.Server.Compatibility;

public enum OriginalClientHandshakeStatus
{
    Success,
    Invalid
}

public readonly record struct OriginalClientHandshakeResult(
    OriginalClientHandshakeStatus Status,
    byte[]? PeerOutboundKey,
    uint PeerSequenceBaseline,
    byte[]? ResponsePayload,
    string? ErrorCode);

/// <summary>
/// Pure server-side counterpart of mpsCipherManager exchange_key_phase2/phase4.
/// It does not open sockets or mutate the active server protocol surface.
/// </summary>
public static class OriginalClientCipherHandshake
{
    public const int SessionKeyLength = 0x10;

    private const int ChecksumSize = sizeof(ushort);
    private const int LengthSize = sizeof(ushort);
    private const int SequenceSize = sizeof(uint);

    private static ReadOnlySpan<byte> BootstrapKey =>
        "{A4C13748-0159-4c54-AEB3-1D68575761B3}"u8;

    public static OriginalClientHandshakeResult ProcessPhase1(
        ReadOnlySpan<byte> encryptedPhase1,
        ReadOnlySpan<byte> serverOutboundKey,
        uint serverSequence)
    {
        ValidateServerOutboundKey(serverOutboundKey);

        if (!HasCompleteCipherBlocks(encryptedPhase1))
        {
            return Invalid("original.handshake.phase1.cipher-length");
        }

        var bootstrapCipher = new OriginalClientBlowfish(BootstrapKey);
        var plaintext = bootstrapCipher.DecryptBlocks(encryptedPhase1);
        if (plaintext.Length < ChecksumSize + LengthSize + SequenceSize)
        {
            return Invalid("original.handshake.phase1.plain-length");
        }

        var keyLength = BinaryPrimitives.ReadUInt16BigEndian(plaintext.AsSpan(ChecksumSize));
        if (keyLength != SessionKeyLength)
        {
            return Invalid("original.handshake.phase1.key-length");
        }

        var meaningfulLength = ChecksumSize + LengthSize + keyLength + SequenceSize;
        if (plaintext.Length < meaningfulLength)
        {
            return Invalid("original.handshake.phase1.plain-length");
        }

        var expectedChecksum = BinaryPrimitives.ReadUInt16BigEndian(plaintext);
        var actualChecksum = OriginalClientWireChecksum.Compute(
            plaintext.AsSpan(ChecksumSize, meaningfulLength - ChecksumSize));
        if (expectedChecksum != actualChecksum)
        {
            return Invalid("original.handshake.phase1.checksum");
        }

        var peerOutboundKey = plaintext.AsSpan(ChecksumSize + LengthSize, keyLength).ToArray();
        var peerSequence = BinaryPrimitives.ReadUInt32BigEndian(
            plaintext.AsSpan(ChecksumSize + LengthSize + keyLength, SequenceSize));
        var response = BuildPhase2Payload(
            bootstrapCipher,
            peerOutboundKey,
            serverOutboundKey,
            serverSequence);

        return new OriginalClientHandshakeResult(
            OriginalClientHandshakeStatus.Success,
            peerOutboundKey,
            unchecked(peerSequence - 1),
            response,
            null);
    }

    public static OriginalClientHandshakeResult ValidatePhase3(
        ReadOnlySpan<byte> encryptedPhase3,
        ReadOnlySpan<byte> serverOutboundKey)
    {
        ValidateServerOutboundKey(serverOutboundKey);

        if (!HasCompleteCipherBlocks(encryptedPhase3))
        {
            return Invalid("original.handshake.phase3.cipher-length");
        }

        var bootstrapCipher = new OriginalClientBlowfish(BootstrapKey);
        var plaintext = bootstrapCipher.DecryptBlocks(encryptedPhase3);
        if (plaintext.Length < ChecksumSize + LengthSize)
        {
            return Invalid("original.handshake.phase3.plain-length");
        }

        var keyLength = BinaryPrimitives.ReadUInt16BigEndian(plaintext.AsSpan(ChecksumSize));
        if (keyLength != SessionKeyLength)
        {
            return Invalid("original.handshake.phase3.key-length");
        }

        var meaningfulLength = ChecksumSize + LengthSize + keyLength;
        if (plaintext.Length < meaningfulLength)
        {
            return Invalid("original.handshake.phase3.plain-length");
        }

        var expectedChecksum = BinaryPrimitives.ReadUInt16BigEndian(plaintext);
        var actualChecksum = OriginalClientWireChecksum.Compute(
            plaintext.AsSpan(ChecksumSize, meaningfulLength - ChecksumSize));
        if (expectedChecksum != actualChecksum)
        {
            return Invalid("original.handshake.phase3.checksum");
        }

        var echoedServerKey = plaintext.AsSpan(ChecksumSize + LengthSize, keyLength);
        if (!CryptographicOperations.FixedTimeEquals(echoedServerKey, serverOutboundKey))
        {
            return Invalid("original.handshake.phase3.key-mismatch");
        }

        return new OriginalClientHandshakeResult(
            OriginalClientHandshakeStatus.Success,
            null,
            0,
            null,
            null);
    }

    private static byte[] BuildPhase2Payload(
        OriginalClientBlowfish bootstrapCipher,
        ReadOnlySpan<byte> peerOutboundKey,
        ReadOnlySpan<byte> serverOutboundKey,
        uint serverSequence)
    {
        var plaintextLength =
            ChecksumSize +
            LengthSize + peerOutboundKey.Length +
            LengthSize + serverOutboundKey.Length +
            SequenceSize;
        var plaintext = new byte[plaintextLength];
        var offset = ChecksumSize;

        BinaryPrimitives.WriteUInt16BigEndian(plaintext.AsSpan(offset), (ushort)peerOutboundKey.Length);
        offset += LengthSize;
        peerOutboundKey.CopyTo(plaintext.AsSpan(offset));
        offset += peerOutboundKey.Length;

        BinaryPrimitives.WriteUInt16BigEndian(plaintext.AsSpan(offset), (ushort)serverOutboundKey.Length);
        offset += LengthSize;
        serverOutboundKey.CopyTo(plaintext.AsSpan(offset));
        offset += serverOutboundKey.Length;

        BinaryPrimitives.WriteUInt32BigEndian(plaintext.AsSpan(offset), serverSequence);
        BinaryPrimitives.WriteUInt16BigEndian(
            plaintext,
            OriginalClientWireChecksum.Compute(plaintext.AsSpan(ChecksumSize)));

        return bootstrapCipher.EncryptPadded(plaintext);
    }

    private static bool HasCompleteCipherBlocks(ReadOnlySpan<byte> ciphertext) =>
        ciphertext.Length >= OriginalClientBlowfish.BlockSize &&
        (ciphertext.Length & (OriginalClientBlowfish.BlockSize - 1)) == 0;

    private static void ValidateServerOutboundKey(ReadOnlySpan<byte> serverOutboundKey)
    {
        if (serverOutboundKey.Length != SessionKeyLength)
        {
            throw new ArgumentException(
                $"The original client requires a {SessionKeyLength}-byte session key.",
                nameof(serverOutboundKey));
        }
    }

    private static OriginalClientHandshakeResult Invalid(string errorCode) =>
        new(OriginalClientHandshakeStatus.Invalid, null, 0, null, errorCode);
}

