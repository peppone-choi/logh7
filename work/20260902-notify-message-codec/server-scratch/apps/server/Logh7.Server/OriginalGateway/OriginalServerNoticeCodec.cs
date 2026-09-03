using System.Buffers.Binary;
using System.Text;

namespace Logh7.Server.OriginalGateway;

public static class OriginalServerNoticeCodec
{
    public const ushort MessageType = 0x2003;
    public const int MaximumEncodedBytes = 1023;

    static OriginalServerNoticeCodec()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
    }

    public static byte[] Encode(string text)
    {
        var body = EncodeText(text, MaximumEncodedBytes);

        var payload = new byte[sizeof(uint) + sizeof(ushort) + body.Length + 1];
        BinaryPrimitives.WriteUInt16BigEndian(payload.AsSpan(sizeof(uint)), MessageType);
        body.CopyTo(payload.AsSpan(sizeof(uint) + sizeof(ushort)));
        return payload;
    }

    public static byte[] EncodeText(string text, int maximumEncodedBytes)
    {
        ArgumentNullException.ThrowIfNull(text);
        if (text.Contains('\0'))
        {
            throw new ArgumentException("ORIGINAL_SERVER_NOTICE_EMBEDDED_NUL", nameof(text));
        }

        var encoding = Encoding.GetEncoding(
            949,
            EncoderFallback.ExceptionFallback,
            DecoderFallback.ExceptionFallback);
        var body = encoding.GetBytes(text);
        if (body.Length > maximumEncodedBytes)
        {
            throw new ArgumentOutOfRangeException(nameof(text), "ORIGINAL_SERVER_NOTICE_LENGTH");
        }

        return body;
    }
}
