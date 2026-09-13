using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

/// <summary>
/// 具申 = 0x0408 CommandSuggestion, answered with 0x0430 ResponseSuggestion.
/// The palette tooltip, constmsg group 0 row 11, is
/// 「[ 具申 ] 味方ユニットへ具申する 実行待機時間48G秒 実行所要時間0G秒」 - make a suggestion to
/// one friendly unit.
/// </summary>
/// <remarks>
/// ORIGINAL_STATIC 2026-09-10, and **not reached by the shipped client**. The
/// request dispatcher's selector table (0x004B864C, index = selector - 1) has an
/// arm at 0x004B81AE that sets <c>ebx = 0x430</c> and <c>esi = 0x408</c>, so
/// 0x0408/0x0430 is a real pair in the protocol - but that arm is selector 0x80,
/// and no call site in the image passes 0x80 to FUN_004B78A0. The palette's
/// six-icon mission sub-panel, which an earlier draft of this authority took for
/// 具申, is 任務 and sends 0x0421
/// (<see cref="OriginalTacticalCommandCodec.MissionCommandType"/>).
///
/// The shapes come from the client's own loggers, which print each field with
/// its name and read it at a fixed offset:
/// <list type="bullet">
/// <item>request, 0x00494EC0, prefix <c>_INF:CommandSuggestion#</c> (.data
/// 0x00769DA4): <c>time=</c> [+0] <c>wait=</c> [+4] <c>id=</c> [+8]
/// <c>unit=</c> [+0xC] <c>mission=</c> [+0x10, byte] <c>target_kind=</c>
/// [+0x11, byte] <c>target=</c> [+0x14] - one unit, not an array, which is what
/// separates it from 任務;</item>
/// <item>response, 0x004A5750, prefix <c>_INF:ResponseSuggestion#</c> (.data
/// 0x0076B938): <c>time=</c> [+0] <c>id=</c> [+4] <c>suggestion_unit=</c> [+8]
/// <c>response=</c> [+0xC, byte] <c>mission=</c> [+0xD, byte]
/// <c>target_kind=</c> [+0xE, byte] <c>target=</c> [+0x10].</item>
/// </list>
/// Struct padding is not a wire field - every recovered command in this authority
/// is byte-packed big-endian - so the bodies are 24 and 21 bytes including the
/// type word.
/// </remarks>
public readonly record struct OriginalSuggestionCommand(
    uint Time,
    uint Wait,
    uint Id,
    uint Unit,
    byte Mission,
    byte TargetKind,
    uint Target);

/// <param name="Response">
/// NEW_DESIGN. The client names the field but nothing recovered says what its
/// values mean, so this authority uses the smallest honest encoding it can
/// defend: <see cref="OriginalSuggestionCodec.ResponseRefused"/> when the
/// suggestion is not delivered and <see cref="OriginalSuggestionCodec.ResponseAccepted"/>
/// when it is. Do not present these as original values.
/// </param>
public readonly record struct OriginalSuggestionResponse(
    uint Time,
    uint Id,
    uint SuggestionUnit,
    byte Response,
    byte Mission,
    byte TargetKind,
    uint Target);

public static class OriginalSuggestionCodec
{
    public const ushort CommandType = 0x0408;
    public const ushort ResponseType = 0x0430;
    public const int CommandSize = sizeof(ushort) + sizeof(uint) * 4 + 2 + sizeof(uint);
    public const int ResponseSize = sizeof(ushort) + sizeof(uint) * 3 + 3 + sizeof(uint);

    /// <summary>NEW_DESIGN, not an original value. See <see cref="OriginalSuggestionResponse"/>.</summary>
    public const byte ResponseRefused = 0;

    /// <summary>NEW_DESIGN, not an original value. See <see cref="OriginalSuggestionResponse"/>.</summary>
    public const byte ResponseAccepted = 1;

    public static bool TryDecode(ReadOnlySpan<byte> payload, out OriginalSuggestionCommand command)
    {
        command = default;
        if (payload.Length != CommandSize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != CommandType)
        {
            return false;
        }
        command = new OriginalSuggestionCommand(
            BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[14..]),
            payload[18],
            payload[19],
            BinaryPrimitives.ReadUInt32BigEndian(payload[20..]));
        return true;
    }

    public static byte[] EncodeCommand(OriginalSuggestionCommand command)
    {
        var frame = new byte[OriginalLoginCodec.MessageCodeSize + CommandSize];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, CommandType);
        BinaryPrimitives.WriteUInt32BigEndian(payload[2..], command.Time);
        BinaryPrimitives.WriteUInt32BigEndian(payload[6..], command.Wait);
        BinaryPrimitives.WriteUInt32BigEndian(payload[10..], command.Id);
        BinaryPrimitives.WriteUInt32BigEndian(payload[14..], command.Unit);
        payload[18] = command.Mission;
        payload[19] = command.TargetKind;
        BinaryPrimitives.WriteUInt32BigEndian(payload[20..], command.Target);
        return frame;
    }

    public static byte[] EncodeResponse(OriginalSuggestionResponse response)
    {
        var frame = new byte[OriginalLoginCodec.MessageCodeSize + ResponseSize];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, ResponseType);
        BinaryPrimitives.WriteUInt32BigEndian(payload[2..], response.Time);
        BinaryPrimitives.WriteUInt32BigEndian(payload[6..], response.Id);
        BinaryPrimitives.WriteUInt32BigEndian(payload[10..], response.SuggestionUnit);
        payload[14] = response.Response;
        payload[15] = response.Mission;
        payload[16] = response.TargetKind;
        BinaryPrimitives.WriteUInt32BigEndian(payload[17..], response.Target);
        return frame;
    }

    public static bool TryDecodeResponse(ReadOnlySpan<byte> payload, out OriginalSuggestionResponse response)
    {
        response = default;
        if (payload.Length != ResponseSize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != ResponseType)
        {
            return false;
        }
        response = new OriginalSuggestionResponse(
            BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
            payload[14],
            payload[15],
            payload[16],
            BinaryPrimitives.ReadUInt32BigEndian(payload[17..]));
        return true;
    }
}
