using System.Net;

namespace Logh7.Server.Hosting;

public sealed record ServeOriginalCommand(
    IPAddress BindAddress,
    int Port,
    IPAddress AdvertiseAddress,
    IPAddress? SessionBindAddress,
    IPAddress? SessionAdvertiseAddress,
    string ReceiptPath);

public sealed record ProvisionDisposableAccountCommand(string SecretPath, string ReceiptPath);

public sealed record ServerInvocation(
    int ExitCode,
    string? StandardOutput,
    string? StandardError,
    object? Command = null);

public static class ServerCommandLine
{
    public static ServerInvocation Parse(string[] arguments)
    {
        if (arguments.Length == 1 && arguments[0] == "--version")
        {
            return new ServerInvocation(0, "Logh7Server 0.1.0 contract-v1", null);
        }

        if (arguments.Length == 9 &&
            arguments[0] == "serve-original" &&
            arguments[1] == "--bind" &&
            arguments[3] == "--port" &&
            arguments[5] == "--advertise" &&
            arguments[7] == "--receipt" &&
            IPAddress.TryParse(arguments[2], out var bindAddress) &&
            IPAddress.TryParse(arguments[6], out var advertiseAddress) &&
            bindAddress.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork &&
            advertiseAddress.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork &&
            !bindAddress.Equals(IPAddress.Any) &&
            int.TryParse(arguments[4], out var port) &&
            port is > 0 and <= ushort.MaxValue &&
            Path.IsPathFullyQualified(arguments[8]))
        {
            return new ServerInvocation(
                0,
                null,
                null,
                new ServeOriginalCommand(bindAddress, port, advertiseAddress, null, null, arguments[8]));
        }

        if (arguments.Length == 13 &&
            arguments[0] == "serve-original" &&
            arguments[1] == "--bind" &&
            arguments[3] == "--port" &&
            arguments[5] == "--advertise" &&
            arguments[7] == "--session-bind" &&
            arguments[9] == "--session-advertise" &&
            arguments[11] == "--receipt" &&
            IPAddress.TryParse(arguments[2], out bindAddress) &&
            IPAddress.TryParse(arguments[6], out advertiseAddress) &&
            IPAddress.TryParse(arguments[8], out var sessionBindAddress) &&
            IPAddress.TryParse(arguments[10], out var sessionAdvertiseAddress) &&
            new[] { bindAddress, advertiseAddress, sessionBindAddress, sessionAdvertiseAddress }
                .All(address => address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork &&
                                !address.Equals(IPAddress.Any)) &&
            int.TryParse(arguments[4], out port) &&
            port is > 0 and <= ushort.MaxValue &&
            Path.IsPathFullyQualified(arguments[12]))
        {
            return new ServerInvocation(
                0,
                null,
                null,
                new ServeOriginalCommand(
                    bindAddress,
                    port,
                    advertiseAddress,
                    sessionBindAddress,
                    sessionAdvertiseAddress,
                    arguments[12]));
        }

        if (arguments.Length == 5 &&
            arguments[0] == "account-provision-disposable" &&
            arguments[1] == "--secret" &&
            arguments[3] == "--receipt" &&
            Path.IsPathFullyQualified(arguments[2]) &&
            Path.IsPathFullyQualified(arguments[4]))
        {
            return new ServerInvocation(
                0,
                null,
                null,
                new ProvisionDisposableAccountCommand(arguments[2], arguments[4]));
        }

        return new ServerInvocation(
            2,
            null,
            "server.command.unknown: 지원되는 명령은 --version, serve-original, account-provision-disposable입니다.");
    }
}
