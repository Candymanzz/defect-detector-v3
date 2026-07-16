using System.Globalization;
using LightServer;

namespace LightServer.Services;

/// <summary>
/// Общий кэш яркости 0..255 для /camera-flash/pair|single и /camera-flash/bank.
/// </summary>
internal static class CameraFlashBrightnessCache
{
    private static readonly object Sync = new();
    private static readonly Dictionary<string, int[]> NetworkByIp = new(StringComparer.OrdinalIgnoreCase);
    private static readonly Dictionary<string, int[]> ComByPort = new(StringComparer.OrdinalIgnoreCase);

    public static int[] GetNetworkOrDefault(string ipAddress, int channelCount)
    {
        lock (Sync)
        {
            if (NetworkByIp.TryGetValue(ipAddress, out int[]? existing) && existing.Length == channelCount)
                return (int[])existing.Clone();
        }

        return Enumerable.Repeat(255, channelCount).ToArray();
    }

    public static int[] MergeNetworkPair(
        string ipAddress,
        int[] deviceChannels,
        int leftChannel,
        int rightChannel,
        int leftPower,
        int rightPower)
    {
        lock (Sync)
        {
            // Без кэша — 255, не 0: иначе соседние каналы того же IP гаснут при первом /pair.
            int[] merged = NetworkByIp.TryGetValue(ipAddress, out int[]? existing)
                           && existing.Length == deviceChannels.Length
                ? (int[])existing.Clone()
                : Enumerable.Repeat(255, deviceChannels.Length).ToArray();

            SetChannel(merged, deviceChannels, leftChannel, leftPower);
            SetChannel(merged, deviceChannels, rightChannel, rightPower);
            return merged;
        }
    }

    public static void RememberNetwork(
        string ipAddress,
        int[] deviceChannels,
        int leftChannel,
        int rightChannel,
        int leftPower,
        int rightPower)
    {
        lock (Sync)
        {
            int[] merged = NetworkByIp.TryGetValue(ipAddress, out int[]? existing)
                           && existing.Length == deviceChannels.Length
                ? (int[])existing.Clone()
                : Enumerable.Repeat(255, deviceChannels.Length).ToArray();

            SetChannel(merged, deviceChannels, leftChannel, leftPower);
            SetChannel(merged, deviceChannels, rightChannel, rightPower);
            NetworkByIp[ipAddress] = merged;
        }
    }

    public static void RememberNetworkFull(string ipAddress, int[] deviceChannels, int[] brightness)
    {
        if (deviceChannels.Length == 0 || brightness.Length != deviceChannels.Length)
            return;
        lock (Sync)
        {
            NetworkByIp[ipAddress] = (int[])brightness.Clone();
        }
    }

    public static void RememberCom(string comPort, int[] channels, int[] powers)
    {
        if (string.IsNullOrWhiteSpace(comPort) || channels.Length == 0 || powers.Length != channels.Length)
            return;

        lock (Sync)
        {
            int[] merged = ComByPort.TryGetValue(comPort, out int[]? existing)
                           && existing.Length >= MaxChannelIndex(channels)
                ? (int[])existing.Clone()
                : new int[Math.Max(4, MaxChannelIndex(channels))];

            for (int i = 0; i < channels.Length; i++)
            {
                int ch = channels[i];
                if (ch >= 1 && ch <= merged.Length)
                    merged[ch - 1] = powers[i];
            }

            ComByPort[comPort] = merged;
        }
    }

    /// <summary>CSV процентов 0–100 для COM-банка по каналам устройств из hardware.</summary>
    public static string BuildComBrightnessCsv(LightHardwareRegistry hardware)
    {
        var percents = new List<int>();
        lock (Sync)
        {
            foreach (LightHardwareDeviceEntry device in hardware.Options.Devices)
            {
                if (!device.Enabled || !device.IsCom || device.Channels.Length == 0)
                    continue;

                ComByPort.TryGetValue(device.ComPort, out int[]? byChannel);
                foreach (int ch in device.Channels)
                {
                    int raw = 255;
                    if (byChannel != null && ch >= 1 && ch <= byChannel.Length && byChannel[ch - 1] > 0)
                        raw = byChannel[ch - 1];
                    percents.Add((int)Math.Round(raw * 100.0 / 255.0));
                }
            }
        }

        return percents.Count == 0
            ? "100"
            : string.Join(",", percents.Select(static p => p.ToString(CultureInfo.InvariantCulture)));
    }

    private static void SetChannel(int[] merged, int[] deviceChannels, int channel, int power)
    {
        int index = Array.IndexOf(deviceChannels, channel);
        if (index >= 0)
            merged[index] = power;
    }

    private static int MaxChannelIndex(int[] channels)
    {
        int max = 0;
        foreach (int ch in channels)
            max = Math.Max(max, ch);
        return max;
    }
}
