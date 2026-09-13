using System.Security.Cryptography;
using Logh7.Server.Authority;

namespace Logh7.Server.OriginalGateway;

public enum OriginalLoginResultCode
{
    Accepted,
    Rejected,
    Malformed
}

public readonly record struct OriginalLoginResult(
    OriginalLoginResultCode Code,
    uint HandoffToken,
    byte[]? MessageCode,
    string? ErrorCode,
    OriginalLoginInputShape? InputShape = null);

public sealed record OriginalLoginInputShape(
    int AccountElementCount,
    int PasswordElementCount,
    bool AccountTerminatorPresent,
    bool PasswordTerminatorPresent,
    bool AccountAsciiPolicyValid,
    bool PasswordAsciiPolicyValid);

public sealed class OriginalLoginAuthority
{
    private readonly IAccountAuthority _authority;
    private readonly HandoffRegistry _handoffs;
    private readonly MetadataOnlyGatewayReceipt _receipt;

    public OriginalLoginAuthority(
        IAccountAuthority authority,
        HandoffRegistry handoffs,
        MetadataOnlyGatewayReceipt receipt)
    {
        _authority = authority ?? throw new ArgumentNullException(nameof(authority));
        _handoffs = handoffs ?? throw new ArgumentNullException(nameof(handoffs));
        _receipt = receipt ?? throw new ArgumentNullException(nameof(receipt));
    }

    public async Task<OriginalLoginResult> ProcessAsync(
        ReadOnlyMemory<byte> applicationPayload,
        CancellationToken cancellationToken)
    {
        var decoded = OriginalLoginCodec.Decode(applicationPayload.Span);
        if (decoded.Status != OriginalLoginParseStatus.Success)
        {
            _receipt.Record("login", "malformed");
            return new OriginalLoginResult(
                OriginalLoginResultCode.Malformed, 0, null, decoded.ErrorCode);
        }

        var message = decoded.Message!.Value;
        var inputShape = CaptureInputShape(message.AccountElements, message.PasswordElements);
        try
        {
            var decision = await _authority.VerifyAsync(
                message.AccountElements,
                message.PasswordElements,
                cancellationToken);
            if (decision.Code != LoginDecisionCode.Accepted)
            {
                _receipt.Record("login", "rejected");
                return new OriginalLoginResult(
                    OriginalLoginResultCode.Rejected, 0, message.Code, "AUTHENTICATION_REJECTED", inputShape);
            }

            var token = _handoffs.Issue(decision.AccountId, decision.NormalizedLogin!);
            _receipt.Record("login", "accepted", decision.AccountId);
            return new OriginalLoginResult(OriginalLoginResultCode.Accepted, token, message.Code, null, inputShape);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(
                System.Runtime.InteropServices.MemoryMarshal.AsBytes(message.PasswordElements.AsSpan()));
            Array.Clear(message.AccountElements);
        }
    }

    private static OriginalLoginInputShape CaptureInputShape(
        ReadOnlySpan<ushort> accountElements,
        ReadOnlySpan<ushort> passwordElements)
    {
        var accountTerminated = !accountElements.IsEmpty && accountElements[^1] == 0;
        var passwordTerminated = !passwordElements.IsEmpty && passwordElements[^1] == 0;
        var account = accountTerminated ? accountElements[..^1] : ReadOnlySpan<ushort>.Empty;
        var password = passwordTerminated ? passwordElements[..^1] : ReadOnlySpan<ushort>.Empty;
        var accountValid = accountTerminated && !account.Contains((ushort)0) &&
                           LoginNamePolicy.TryNormalize(account, out _);
        var passwordValid = passwordTerminated && !password.IsEmpty && !password.Contains((ushort)0);
        foreach (var element in password)
        {
            if (element is < 0x21 or > 0x7e)
            {
                passwordValid = false;
                break;
            }
        }

        return new OriginalLoginInputShape(
            accountElements.Length,
            passwordElements.Length,
            accountTerminated,
            passwordTerminated,
            accountValid,
            passwordValid);
    }
}
