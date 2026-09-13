using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Threading.Channels;
using Logh7.Server.Compatibility;
using Logh7.Server.Hosting;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalTransportHeartbeatTests
{
    [Theory]
    [InlineData(NaturalAuthoritySessionState.AwaitPhase1)]
    [InlineData(NaturalAuthoritySessionState.AwaitPhase3)]
    [InlineData(NaturalAuthoritySessionState.AwaitFirstApplication)]
    [InlineData(NaturalAuthoritySessionState.AwaitLobbyLogin)]
    [InlineData(NaturalAuthoritySessionState.LobbyReady)]
    [InlineData(NaturalAuthoritySessionState.SessionServerReady)]
    public async Task Empty_transport_heartbeat_preserves_active_session_without_application_reply(
        NaturalAuthoritySessionState state)
    {
        // Break caught: native timer's literal outer0002 enters the application
        // reject branch, or creates an unsolicited reply/cipher sequence.
        var session = CreateSession();
        SetState(session, state);
        var result = await session.ProcessAsync(0x0002, ReadOnlyMemory<byte>.Empty, CancellationToken.None);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        Assert.Equal(state, session.State);
        Assert.Null(result.ResponseOuterControl);
        Assert.Null(result.ResponsePayload);
        Assert.Null(result.ObservedApplicationType);
        Assert.Null(result.AdditionalResponses);
        Assert.Null(result.ResponsesBeforePrimary);
    }

    [Theory]
    [InlineData(NaturalAuthoritySessionState.Rejected)]
    [InlineData(NaturalAuthoritySessionState.LoginAcceptedSent)]
    [InlineData(NaturalAuthoritySessionState.LobbyRedirectSent)]
    public async Task Heartbeat_does_not_reopen_a_terminal_session(NaturalAuthoritySessionState state)
    {
        var session = CreateSession();
        SetState(session, state);
        var result = await session.ProcessAsync(0x0002, ReadOnlyMemory<byte>.Empty, CancellationToken.None);
        Assert.Equal(NaturalAuthoritySessionStatus.Invalid, result.Status);
    }

    [Fact]
    public async Task Control_two_with_payload_is_not_a_heartbeat()
    {
        var result = await CreateSession().ProcessAsync(0x0002, new byte[] { 0 }, CancellationToken.None);
        Assert.Equal(NaturalAuthoritySessionStatus.Invalid, result.Status);
    }

    [Fact]
    public async Task Real_tcp_heartbeat_keeps_connection_and_cipher_sequence_for_next_query()
    {
        // Native frame is length0002/control0002. The following encrypted
        // application sequence remains1; first server application sequence1.
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        using var client = new TcpClient();
        var connect = client.ConnectAsync(IPAddress.Loopback, ((IPEndPoint)listener.LocalEndpoint).Port, timeout.Token);
        using var peer = await listener.AcceptTcpClientAsync(timeout.Token);
        await connect;
        var session = CreateSession();
        var notifications = Channel.CreateUnbounded<byte[]>();
        var server = Task.Run(async () =>
        {
            try
            {
                await OriginalConnectionPump.RunAsync(peer.GetStream(), notifications.Reader,
                    async (body, token) =>
                    {
                        var result = await session.ProcessAsync(BinaryPrimitives.ReadUInt16BigEndian(body), body.AsMemory(2), token);
                        if (result.Status != NaturalAuthoritySessionStatus.Success) return false;
                        if (result.ResponseOuterControl is ushort control)
                        {
                            var frame = OriginalClientTransportFrameWriter.Encode(result.ResponseTransportPrefix ?? [], control, result.ResponsePayload!);
                            await peer.GetStream().WriteAsync(frame, token);
                            await peer.GetStream().FlushAsync(token);
                        }
                        return true;
                    }, (_, _) => Task.CompletedTask, timeout.Token);
            }
            finally { peer.Close(); notifications.Writer.TryComplete(); }
        }, timeout.Token);
        try
        {
            var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
            var query = OriginalClientTransportFrameWriter.Encode([], 0x0030,
                OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0300"), key, 1));
            await client.GetStream().WriteAsync(Convert.FromHexString("0002000200020002").Concat(query).ToArray(), timeout.Token);
            var prefix = new byte[2];
            await client.GetStream().ReadExactlyAsync(prefix, timeout.Token);
            var response = new byte[BinaryPrimitives.ReadUInt16BigEndian(prefix)];
            await client.GetStream().ReadExactlyAsync(response, timeout.Token);
            Assert.Equal("000000000030", Convert.ToHexString(response.AsSpan(0, 6)));
            var decoded = OriginalClientInnerFrameCodec.Decode(response.AsSpan(6), key, 0);
            Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
            Assert.Equal((ushort)0x0301, BinaryPrimitives.ReadUInt16BigEndian(decoded.Payload!.AsSpan(4)));
            Assert.Equal(1u, decoded.Sequence);
            Assert.Equal(NaturalAuthoritySessionState.SessionServerReady, session.State);
        }
        finally
        {
            client.Close();
            await server;
        }
    }

    private static NaturalAuthoritySession CreateSession() =>
        OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,
            new byte[OriginalClientCipherHandshake.SessionKeyLength]);

    private static void SetState(NaturalAuthoritySession session, NaturalAuthoritySessionState state) =>
        typeof(NaturalAuthoritySession).GetProperty(nameof(NaturalAuthoritySession.State))!.SetValue(session, state);
}
