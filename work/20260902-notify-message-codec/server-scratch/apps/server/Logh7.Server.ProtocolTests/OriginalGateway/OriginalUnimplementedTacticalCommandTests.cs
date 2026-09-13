using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// An unimplemented tactical command must never end the player's game. 旋回
/// 0x0401 closed the session until it was recovered; 鼓舞 0x0409 still did.
/// </summary>
public sealed class OriginalUnimplementedTacticalCommandTests
{
    // ORIGINAL_OBSERVED: the exact 0x0409 body the native 鼓舞 icon sent on
    // 2026-09-09 (run 20260906T181000Z-departure-v124, authority v59). The
    // client then showed 切断 / サーバーから切断されました。ゲームを終了します。
    private const string NativeEncourageBody = "040908D24738000000020000000200000002";

    /// <summary>
    /// 0x0409 is implemented now (see OriginalFlagshipEncouragePostgresTests), so
    /// this is the standing proof that it no longer ends the session: without a
    /// world it is refused on screen, not disconnected.
    /// </summary>
    [Fact]
    public async Task The_native_encourage_command_is_refused_on_screen_and_keeps_the_session_open()
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key);

        var result = await session.ProcessAsync(0x0030, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString(NativeEncourageBody), key, 1), TestContext.Current.CancellationToken);

        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        Assert.Equal(NaturalAuthoritySessionState.SessionServerReady, session.State);
        Assert.Contains("command-reject=FLAGSHIP_ENCOURAGE_", result.ResponseMetadata);
        var frame = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0).Payload!;
        Assert.Equal(OriginalNotifyMessageCodec.InvalidMessageType,
            BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)));
    }

    /// <summary>
    /// Every type in the client's table is handled now, so the self-documenting
    /// refusal has no unhandled type left to answer. What it still guarantees - and
    /// what this asserts - is that a body the handler cannot read is answered
    /// visibly rather than dropping the session.
    /// </summary>
    [Fact]
    public async Task A_still_unimplemented_tactical_command_documents_itself_on_the_wire()
    {
        // 0x040B CommandAdmission with a body its handler cannot read: the count
        // byte does not match the length. 0x0407 白兵戦 and then 0x040B stood here
        // as examples of *unhandled* types, until every type had a handler.
        const string body = "040B08D24738000000020000000200000002";
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key);

        var result = await session.ProcessAsync(0x0030, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString(body), key, 1), TestContext.Current.CancellationToken);

        // Answered, not dropped - which is the whole point of the path.
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        Assert.Equal(NaturalAuthoritySessionState.SessionServerReady, session.State);
        Assert.Contains("original.tactical-admission.request-shape",
            result.ResponseMetadata ?? string.Empty);
    }

    /// <summary>
    /// The refusal is scoped to the 操艦 block. A type outside it keeps the
    /// original rejection, so a genuinely wrong frame is still a protocol
    /// error rather than a silently swallowed one.
    /// </summary>
    /// <remarks>
    /// The frame here is 0x0423 NotifyMovedShip - an answer the authority sends,
    /// never a command the client sends, so a client that sends it is wrong.
    /// This test used to use 0x0410, back when the graceful band was the
    /// hand-written 0x0400..0x040F. 0x0410 is <c>CommandEvacuateTroops</c>
    /// (陸戦解除, constmsg group 0 row 18) - a command the shipped client's own
    /// palette can send - so the old bound made a real palette button drop the
    /// player's connection. <see cref="OriginalTacticalCommandCatalog"/> replaced
    /// the bound with the client's own table.
    /// </remarks>
    [Fact]
    public async Task A_type_outside_the_tactical_block_is_still_rejected()
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key);

        var result = await session.ProcessAsync(0x0030, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("042308D2473800000002"), key, 1), TestContext.Current.CancellationToken);

        Assert.Equal(NaturalAuthoritySessionStatus.Invalid, result.Status);
        Assert.Equal("original.session-server.unexpected-application-type", result.ErrorCode);
    }

    /// <summary>
    /// Every command in the client's own table is answered rather than dropped -
    /// the guarantee that outlived the unimplemented band. Twelve of these were
    /// protocol violations until the catalogue replaced the hand-written 0x040F
    /// bound, and none of them may close a session now that all 35 are handled.
    /// </summary>
    [Fact]
    public async Task Every_catalogued_command_is_answered_instead_of_dropping_the_session()
    {
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        for (var type = OriginalTacticalCommandCatalog.FirstType;
             type <= OriginalTacticalCommandCatalog.LastType;
             type++)
        {
            var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key);
            // A header with no body: readable as a frame, unreadable as any of the
            // commands, so every handler must refuse it on its own terms.
            var body = type.ToString("X4") + "08D2473800000002";

            var result = await session.ProcessAsync(0x0030, OriginalClientInnerFrameCodec.Encode(
                Convert.FromHexString(body), key, 1), TestContext.Current.CancellationToken);

            Assert.True(session.State == NaturalAuthoritySessionState.SessionServerReady,
                $"0x{type:X4} closed the session: {result.ErrorCode}");
            // Answered either way; the session is the thing that must survive.
        }
    }
}
