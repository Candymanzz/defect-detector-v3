using System.Globalization;
using System.Text.RegularExpressions;
using MvCameraControl;

namespace LightServer.Services;

/// <summary>
/// SetEnumSerialPorts — глобальный фильтр MVS SDK: влияет на все потоки.
/// EnumGate сериализует только перечисление; открытые device работают параллельно (IsolatedComPortLight).
/// </summary>
internal static class MvsComPortEnumerator
{
    private static readonly Regex ComNumberRegex = new(@"COM(\d+)", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly object EnumGate = new();

    private const DeviceTLayerType SerialLayers =
        DeviceTLayerType.MvCameraLinkDevice
        | DeviceTLayerType.MvVirGigEDevice
        | DeviceTLayerType.MvVirUsbDevice;

    /// <summary>Один раз SetEnumSerialPorts(все COM) — так MVS открывает 2+ устройства (иначе Open 0x800000FF).</summary>
    public static (bool ok, string? error, List<IDeviceInfo>? list, Dictionary<string, int> comToIndex) EnumerateBankPorts(
        IReadOnlyList<string> comPorts)
    {
        var ordered = comPorts
            .Select(NormalizeComPort)
            .Where(static p => p.Length > 0)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(static p => p, StringComparer.OrdinalIgnoreCase)
            .ToList();

        if (ordered.Count == 0)
            return (false, "Нет COM для перечисления.", null, []);

        lock (EnumGate)
        {
            var (resolveOk, resolveErr, sdkPorts) = ResolveSdkSerialPortNames(ordered);
            if (!resolveOk)
                return (false, resolveErr, null, []);

            int setRet = DeviceEnumerator.SetEnumSerialPorts(sdkPorts);
            if (setRet != MvError.MV_OK)
                return (false, $"SetEnumSerialPorts failed: 0x{setRet:x8}", null, []);

            int ret = DeviceEnumerator.EnumDevices(SerialLayers, out List<IDeviceInfo> list);
            if (ret != MvError.MV_OK)
                return (false, $"EnumDevices failed: 0x{ret:x8}", null, []);

            var map = BuildComIndexMap(list);
            return (true, null, list, map);
        }
    }

    public static string DescribeDevice(IDeviceInfo d)
    {
        string port = d is ICamlDeviceInfo caml ? NormalizeComPort(caml.PortID) : "?";
        return $"{d.ModelName} @ {port} ({d.SerialNumber})";
    }

    private static Dictionary<string, int> BuildComIndexMap(List<IDeviceInfo> list)
    {
        var map = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        for (int i = 0; i < list.Count; i++)
        {
            if (list[i] is not ICamlDeviceInfo caml)
                continue;

            string port = NormalizeComPort(caml.PortID);
            if (port.Length > 0)
                map[port] = i;
        }

        return map;
    }

    public static (bool ok, string? error, int deviceIndex, List<IDeviceInfo>? list) EnumerateSinglePort(string comPort)
    {
        string com = NormalizeComPort(comPort);
        if (com.Length == 0)
            return (false, "Пустой COM.", -1, null);

        lock (EnumGate)
        {
            var (resolveOk, resolveErr, sdkPorts) = ResolveSdkSerialPortNames([com]);
            if (!resolveOk)
                return (false, resolveErr, -1, null);

            int setRet = DeviceEnumerator.SetEnumSerialPorts(sdkPorts);
            if (setRet != MvError.MV_OK)
                return (false, $"SetEnumSerialPorts([{string.Join(", ", sdkPorts)}]) failed: 0x{setRet:x8}", -1, null);

            int ret = DeviceEnumerator.EnumDevices(SerialLayers, out List<IDeviceInfo> list);
            if (ret != MvError.MV_OK)
                return (false, $"EnumDevices failed: 0x{ret:x8}", -1, null);

            int idx = FindDeviceIndex(list, com);
            if (idx < 0)
                return (false, $"Устройство на {com} не найдено (enum count={list.Count}).", -1, list);

            return (true, null, idx, list);
        }
    }

    private static int FindDeviceIndex(List<IDeviceInfo> list, string com)
    {
        for (int i = 0; i < list.Count; i++)
        {
            if (list[i] is ICamlDeviceInfo caml
                && string.Equals(NormalizeComPort(caml.PortID), com, StringComparison.OrdinalIgnoreCase))
                return i;
        }

        string marker = $"COM_Port#{com}";
        for (int i = 0; i < list.Count; i++)
        {
            if (BuildSearchBlob(list[i]).Contains(marker, StringComparison.OrdinalIgnoreCase))
                return i;
        }

        return list.Count == 1 ? 0 : -1;
    }

    private static string BuildSearchBlob(IDeviceInfo d)
    {
        if (d is ICamlDeviceInfo caml && !string.IsNullOrWhiteSpace(caml.PortID))
            return $"{d.ModelName} {caml.PortID}";
        return d.ModelName ?? "";
    }

    private static (bool ok, string? err, List<string> ports) ResolveSdkSerialPortNames(IReadOnlyList<string> userPortList)
    {
        int hostRet = DeviceEnumerator.GetSerialPortList(out List<string> hostPorts);
        hostPorts ??= [];

        var resolved = new List<string>();
        foreach (string raw in userPortList)
        {
            string com = NormalizeComPort(raw);
            if (com.Length == 0)
                continue;

            string marker = $"COM_Port#{com}";
            string? sdkName = hostPorts.FirstOrDefault(h =>
                string.Equals(h, marker, StringComparison.OrdinalIgnoreCase)
                || h.EndsWith($"#{com}", StringComparison.OrdinalIgnoreCase));

            resolved.Add(sdkName ?? marker);
        }

        if (resolved.Count == 0)
            return (false, "Не указаны COM-порты.", []);

        if (hostRet != MvError.MV_OK && hostPorts.Count == 0)
            return (true, null, resolved);

        return (true, null, resolved);
    }

    /// <summary>COM1 из "COM1", "3", "COM_Port#COM3", "COM_PORT#COM1".</summary>
    public static string NormalizeComPort(string p)
    {
        p = p.Trim();
        if (p.Length == 0)
            return "";

        MatchCollection matches = ComNumberRegex.Matches(p);
        if (matches.Count > 0)
        {
            string digits = matches[^1].Groups[1].Value;
            if (int.TryParse(digits, NumberStyles.Integer, CultureInfo.InvariantCulture, out int n) && n > 0)
                return "COM" + n;
        }

        if (int.TryParse(p, NumberStyles.Integer, CultureInfo.InvariantCulture, out int num) && num > 0)
            return "COM" + num;

        return p.ToUpperInvariant();
    }
}
