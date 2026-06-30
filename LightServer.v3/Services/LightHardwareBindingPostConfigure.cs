using LightServer.Configuration;
using LightServer;
using Microsoft.Extensions.Options;

namespace LightServer.Services;

/// <summary>Применяет light_hardware.yaml к ComLightDevices и SerialLight.EnumPorts.</summary>
public sealed class LightHardwareBindingPostConfigure :
    IPostConfigureOptions<ComLightDevicesOptions>,
    IPostConfigureOptions<SerialLightOptions>
{
    private readonly LightHardwareLoadResult _load;

    public LightHardwareBindingPostConfigure(LightHardwareLoadResult load) => _load = load;

    public void PostConfigure(string? name, ComLightDevicesOptions options)
    {
        if (!_load.HasYamlDevices)
        {
            if (options.Devices.Length > 0)
                options.Devices = ComLightBankService.DeduplicateDevicesForOptions(options.Devices);
            return;
        }

        options.InitializeOnStartup = _load.Options.InitializeOnStartup;
        options.Devices = BuildComDeviceEntries(_load.Options.Devices);
    }

    public void PostConfigure(string? name, SerialLightOptions options)
    {
        if (!_load.HasYamlDevices)
            return;

        options.EnumPorts = _load.Options.Devices
            .Where(static d => d.Enabled && d.IsCom && d.ComPort.Length > 0)
            .Select(static d => d.ComPort)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(static p => p, StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    internal static ComLightDeviceEntry[] BuildComDeviceEntries(IEnumerable<LightHardwareDeviceEntry> devices)
    {
        var entries = new List<ComLightDeviceEntry>();
        foreach (LightHardwareDeviceEntry device in devices)
        {
            if (!device.Enabled || !device.IsCom || device.ComPort.Length == 0)
                continue;

            entries.Add(new ComLightDeviceEntry
            {
                DeviceId = device.Id,
                ComPort = MvsComPortEnumerator.NormalizeComPort(device.ComPort),
                Channels = NormalizeConfiguredChannels(device.Channels)
            });
        }

        return ComLightBankService.DeduplicateDevicesForOptions(entries.ToArray());
    }

    private static int[] NormalizeConfiguredChannels(int[] channels)
    {
        var unique = new List<int>(4);
        foreach (int ch in channels)
        {
            if (ch is >= 1 and <= 4 && !unique.Contains(ch))
                unique.Add(ch);
        }

        return unique.ToArray();
    }
}
