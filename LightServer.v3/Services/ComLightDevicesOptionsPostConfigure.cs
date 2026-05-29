using LightServer;
using Microsoft.Extensions.Options;

namespace LightServer.Services;

/// <summary>После bind убираем дубли COM/каналов (склейка default + appsettings).</summary>
public sealed class ComLightDevicesOptionsPostConfigure : IPostConfigureOptions<ComLightDevicesOptions>
{
    public void PostConfigure(string? name, ComLightDevicesOptions options)
    {
        if (options.Devices.Length == 0)
            return;

        options.Devices = ComLightBankService.DeduplicateDevicesForOptions(options.Devices);
    }
}
