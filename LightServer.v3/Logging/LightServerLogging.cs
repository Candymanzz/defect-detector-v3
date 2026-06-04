using Microsoft.Extensions.Configuration;

namespace LightServer.Logging;

internal static class LightServerLogging
{
    public const string ConfigKey = "Log";

    public static bool IsEnabled(IConfiguration configuration) =>
        configuration.GetValue(ConfigKey, false);
}
