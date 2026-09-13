using System.Buffers.Binary;

namespace Logh7.Server.Compatibility;

/// <summary>
/// Byte-compatible Blowfish variant recovered from G7MTClient.exe.
/// This is intentionally separate from standard Blowfish: every byte in the
/// initial P/S state is one greater than the published Blowfish state, and
/// 32-bit block halves are loaded and stored little-endian.
/// </summary>
public sealed class OriginalClientBlowfish
{
    public const int BlockSize = 8;
    public const int MaximumKeyLength = 56;

    private const int PCount = 18;
    private const int SBoxCount = 4;
    private const int SBoxSize = 256;
    private const byte StoredStateXor = 0x91;

    // G7MTClient.exe SHA-256:
    // bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16
    // Concatenated bytes at 0x007b6ae4 (0x48) and 0x007b6ba8 (0x1000).
    // The executable XORs each stored byte with 0x91 before copying the state.
    private const string ObfuscatedInitialStateBase64 = """
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
ME7MNr+3m9ExdphSovPeKc1y6V51cQHJduJVqg==
""";

    private static readonly uint[] InitialState = DecodeInitialState();

    private readonly uint[] _p = new uint[PCount];
    private readonly uint[][] _s =
    [
        new uint[SBoxSize],
        new uint[SBoxSize],
        new uint[SBoxSize],
        new uint[SBoxSize]
    ];

    public OriginalClientBlowfish(ReadOnlySpan<byte> key)
    {
        ArgumentOutOfRangeException.ThrowIfZero(key.Length);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(key.Length, MaximumKeyLength);

        InitialState.AsSpan(0, PCount).CopyTo(_p);
        for (var box = 0; box < SBoxCount; box++)
        {
            InitialState.AsSpan(PCount + (box * SBoxSize), SBoxSize).CopyTo(_s[box]);
        }

        ExpandKey(key);
    }

    public byte[] EncryptPadded(ReadOnlySpan<byte> plaintext)
    {
        var paddedLength = checked((plaintext.Length + (BlockSize - 1)) & ~(BlockSize - 1));
        var result = new byte[paddedLength];
        plaintext.CopyTo(result);
        TransformBlocks(result, encipher: true);
        return result;
    }

    public byte[] DecryptBlocks(ReadOnlySpan<byte> ciphertext)
    {
        if ((ciphertext.Length & (BlockSize - 1)) != 0)
        {
            throw new ArgumentException(
                "Original-client Blowfish ciphertext must contain complete 8-byte blocks.",
                nameof(ciphertext));
        }

        var result = ciphertext.ToArray();
        TransformBlocks(result, encipher: false);
        return result;
    }

    private static uint[] DecodeInitialState()
    {
        var stored = Convert.FromBase64String(ObfuscatedInitialStateBase64);
        var expectedLength = (PCount + (SBoxCount * SBoxSize)) * sizeof(uint);
        if (stored.Length != expectedLength)
        {
            throw new InvalidOperationException("The original-client Blowfish state has an invalid length.");
        }

        var words = new uint[PCount + (SBoxCount * SBoxSize)];
        Span<byte> decodedWord = stackalloc byte[sizeof(uint)];
        for (var index = 0; index < words.Length; index++)
        {
            for (var byteIndex = 0; byteIndex < sizeof(uint); byteIndex++)
            {
                decodedWord[byteIndex] =
                    (byte)(stored[(index * sizeof(uint)) + byteIndex] ^ StoredStateXor);
            }

            words[index] = BinaryPrimitives.ReadUInt32LittleEndian(decodedWord);
        }

        return words;
    }

    private void ExpandKey(ReadOnlySpan<byte> key)
    {
        var keyIndex = 0;
        for (var pIndex = 0; pIndex < _p.Length; pIndex++)
        {
            uint keyWord = 0;
            for (var byteIndex = 0; byteIndex < sizeof(uint); byteIndex++)
            {
                keyWord = (keyWord << 8) | key[keyIndex];
                keyIndex = (keyIndex + 1) % key.Length;
            }

            _p[pIndex] ^= keyWord;
        }

        uint left = 0;
        uint right = 0;
        for (var pIndex = 0; pIndex < _p.Length; pIndex += 2)
        {
            Encipher(ref left, ref right);
            _p[pIndex] = left;
            _p[pIndex + 1] = right;
        }

        foreach (var box in _s)
        {
            for (var entry = 0; entry < box.Length; entry += 2)
            {
                Encipher(ref left, ref right);
                box[entry] = left;
                box[entry + 1] = right;
            }
        }
    }

    private void TransformBlocks(Span<byte> bytes, bool encipher)
    {
        for (var offset = 0; offset < bytes.Length; offset += BlockSize)
        {
            var left = BinaryPrimitives.ReadUInt32LittleEndian(bytes[offset..]);
            var right = BinaryPrimitives.ReadUInt32LittleEndian(bytes[(offset + sizeof(uint))..]);

            if (encipher)
            {
                Encipher(ref left, ref right);
            }
            else
            {
                Decipher(ref left, ref right);
            }

            BinaryPrimitives.WriteUInt32LittleEndian(bytes[offset..], left);
            BinaryPrimitives.WriteUInt32LittleEndian(bytes[(offset + sizeof(uint))..], right);
        }
    }

    private void Encipher(ref uint left, ref uint right)
    {
        unchecked
        {
            for (var round = 0; round < 16; round++)
            {
                left ^= _p[round];
                right = RoundF(left) ^ right;
                (left, right) = (right, left);
            }

            (left, right) = (right, left);
            right ^= _p[16];
            left ^= _p[17];
        }
    }

    private void Decipher(ref uint left, ref uint right)
    {
        unchecked
        {
            for (var round = 17; round > 1; round--)
            {
                left ^= _p[round];
                right = RoundF(left) ^ right;
                (left, right) = (right, left);
            }

            (left, right) = (right, left);
            right ^= _p[1];
            left ^= _p[0];
        }
    }

    private uint RoundF(uint value)
    {
        unchecked
        {
            var a = (byte)(value >> 24);
            var b = (byte)(value >> 16);
            var c = (byte)(value >> 8);
            var d = (byte)value;
            return ((_s[0][a] + _s[1][b]) ^ _s[2][c]) + _s[3][d];
        }
    }
}


