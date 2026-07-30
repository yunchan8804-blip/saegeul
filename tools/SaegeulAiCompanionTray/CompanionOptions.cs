using System.IO;

namespace SaegeulAiCompanionTray;

internal sealed record CompanionOptions(
    string CompanionPath,
    int GatewayPort,
    int TailscaleHttpsPort
)
{
    public static CompanionOptions Parse(string[] args)
    {
        string? companionPath = null;
        var gatewayPort = 9211;
        var tailscaleHttpsPort = 9210;

        for (var index = 0; index < args.Length; index++)
        {
            var value = args[index];
            string NextValue()
            {
                if (++index >= args.Length)
                    throw new ArgumentException($"{value} 뒤에 값이 필요해.");
                return args[index];
            }

            switch (value)
            {
                case "--companion":
                    companionPath = NextValue();
                    break;
                case "--gateway-port":
                    gatewayPort = ParsePort(NextValue(), value);
                    break;
                case "--tailscale-https-port":
                    tailscaleHttpsPort = ParsePort(NextValue(), value);
                    break;
                default:
                    throw new ArgumentException($"알 수 없는 옵션이야: {value}");
            }
        }

        if (string.IsNullOrWhiteSpace(companionPath))
            throw new ArgumentException("--companion 경로가 필요해.");
        companionPath = Path.GetFullPath(companionPath);
        if (!File.Exists(companionPath))
            throw new FileNotFoundException("AI companion Python 파일을 찾지 못했어.", companionPath);

        return new CompanionOptions(companionPath, gatewayPort, tailscaleHttpsPort);
    }

    private static int ParsePort(string value, string option)
    {
        if (!int.TryParse(value, out var port) || port is < 1 or > 65535)
            throw new ArgumentException($"{option} 포트가 올바르지 않아: {value}");
        return port;
    }
}
