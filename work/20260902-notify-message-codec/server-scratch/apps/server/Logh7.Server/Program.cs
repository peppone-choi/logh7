using System.Text;
using Logh7.Server.Hosting;

var invocation = ServerCommandLine.Parse(args);

using var standardOutput = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(encoderShouldEmitUTF8Identifier: false))
{
    AutoFlush = true
};
using var standardError = new StreamWriter(Console.OpenStandardError(), new UTF8Encoding(encoderShouldEmitUTF8Identifier: false))
{
    AutoFlush = true
};

if (invocation.StandardOutput is not null)
{
    standardOutput.WriteLine(invocation.StandardOutput);
}

if (invocation.StandardError is not null)
{
    standardError.WriteLine(invocation.StandardError);
}

if (invocation.Command is ServeOriginalCommand serveOriginal)
{
    using var stop = new CancellationTokenSource();
    ConsoleCancelEventHandler handler = (_, eventArgs) =>
    {
        eventArgs.Cancel = true;
        stop.Cancel();
    };
    Console.CancelKeyPress += handler;
    try
    {
        return await NaturalAuthorityServerCommand.RunAsync(
            serveOriginal,
            standardOutput,
            standardError,
            stop.Token);
    }
    finally
    {
        Console.CancelKeyPress -= handler;
    }
}

if (invocation.Command is ProvisionDisposableAccountCommand provisionDisposable)
{
    return await DisposableAccountProvisionCommand.RunAsync(
        provisionDisposable,
        standardOutput,
        standardError,
        CancellationToken.None);
}

return invocation.ExitCode;
