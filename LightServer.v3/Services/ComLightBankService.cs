using System.Globalization;
using LightServer.Models;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace LightServer.Services;

/// <summary>Статический банк: по одной долгоживущей сессии на каждый COM из конфига.</summary>
public sealed class ComLightBankService
{
    private readonly ComLightDevicesOptions _devices;
    private readonly ComLightIsolatedBank _isolatedBank;
    private readonly ILogger<ComLightBankService> _log;
    private readonly object _initLock = new();
    private bool _initialized;
    private string? _initError;

    private readonly ComLightDeviceEntry[] _uniqueDevices;

    public ComLightBankService(
        IOptions<ComLightDevicesOptions> devices,
        ComLightIsolatedBank isolatedBank,
        ILogger<ComLightBankService> log)
    {
        _devices = devices.Value;
        _uniqueDevices = DeduplicateDevices(_devices.Devices);
        _isolatedBank = isolatedBank;
        _log = log;
    }

    public bool IsInitialized => _initialized && _isolatedBank.ReadyComPorts.Count > 0;

    public bool IsPartial => _isolatedBank.IsPartial;

    public IReadOnlyList<ComLightDeviceEntry> ConfiguredDevices => _uniqueDevices;

    public IReadOnlyList<ComLightDeviceEntry> ReadyDevices =>
        _uniqueDevices.Where(d => _isolatedBank.IsPortReady(d.ComPort)).ToList();

    public IReadOnlyDictionary<string, string> SkippedPorts => _isolatedBank.SkippedPorts;

    public (bool ok, string? error) EnsureInitialized()
    {
        if (_initialized && _isolatedBank.ReadyComPorts.Count > 0)
            return (true, _initError);

        lock (_initLock)
        {
            if (_initialized && _isolatedBank.ReadyComPorts.Count > 0)
                return (true, _initError);

            if (_uniqueDevices.Length == 0)
            {
                _initError = "COM-устройства не настроены — задайте config/blocks/51-light-hardware.yaml или ComLightDevices в appsettings.json.";
                _initialized = false;
                return (false, _initError);
            }

            var (ok, err) = _isolatedBank.InitializeAll();
            _initError = err;
            _initialized = ok;
            if (ok)
            {
                _log.LogInformation(
                    "ComLightBank: готово {ReadyCount}/{Total} COM ({ReadyPorts})",
                    _isolatedBank.ReadyComPorts.Count,
                    _uniqueDevices.Length,
                    string.Join(", ", _isolatedBank.ReadyComPorts));
                if (_isolatedBank.SkippedPorts.Count > 0)
                    _log.LogWarning(
                        "ComLightBank: пропущены {Skipped}",
                        string.Join(", ", _isolatedBank.SkippedPorts.Keys));
            }
            else
                _log.LogError("ComLightBank: инициализация не удалась: {Error}", err);

            return (ok, err);
        }
    }

    /// <summary>on/off для всех COM; brightness — % через запятую (0–100), по умолчанию 100.</summary>
    public ComLightStateResponse SetState(string? state, string? brightnessCsv)
    {
        string normalized = (state ?? "off").Trim();
        bool turnOn = normalized.Equals("on", StringComparison.OrdinalIgnoreCase)
            || normalized.Equals("1", StringComparison.OrdinalIgnoreCase);
        bool turnOff = normalized.Equals("off", StringComparison.OrdinalIgnoreCase)
            || normalized.Equals("0", StringComparison.OrdinalIgnoreCase);

        if (!turnOn && !turnOff)
        {
            return new ComLightStateResponse
            {
                Success = false,
                Error = "state должен быть on или off.",
                State = normalized
            };
        }

        var (ready, readyErr) = EnsureInitialized();
        if (!ready)
        {
            return new ComLightStateResponse
            {
                Success = false,
                Error = readyErr ?? "ComLightBank не инициализирован.",
                State = turnOff ? "off" : "on",
                Brightness = brightnessCsv
            };
        }

        if (turnOff)
        {
            ComLightApplyResponse off = ApplyAllOffInternal();
            return new ComLightStateResponse
            {
                Success = off.Success,
                Message = off.Success ? BuildOffMessage(off.Results) : null,
                Error = off.Error,
                State = "off",
                Results = off.Results
            };
        }

        IReadOnlyList<ComLightDeviceEntry> activeDevices = ReadyDevices;
        int totalChannels = activeDevices.Sum(static d => d.Channels.Length);
        int[] percents;
        try
        {
            percents = ParseBrightnessPercents(brightnessCsv, totalChannels);
        }
        catch (Exception ex)
        {
            return new ComLightStateResponse
            {
                Success = false,
                Error = ex.Message,
                State = "on",
                Brightness = brightnessCsv
            };
        }

        List<ComPortFlashCommand> flashCommands = BuildFlashCommands(activeDevices, percents);
        var cmdByCom = flashCommands.ToDictionary(static c => c.ComPort, static c => c, StringComparer.OrdinalIgnoreCase);
        var applied = _isolatedBank.ApplyAllOn(flashCommands);
        var results = MergeResults(applied, cmdByCom, "On");

        bool success = EvaluateSuccess(results);
        string appliedCsv = string.Join(",", percents);
        return new ComLightStateResponse
        {
            Success = success,
            Message = success ? BuildOnMessage(results, appliedCsv) : null,
            Error = success ? null : "Часть подключённых COM не включилась.",
            State = "on",
            Brightness = appliedCsv,
            Results = results
        };
    }

    private List<ComPortFlashCommand> BuildFlashCommands(IReadOnlyList<ComLightDeviceEntry> devices, int[] percents)
    {
        var commands = new List<ComPortFlashCommand>(devices.Count);
        int idx = 0;
        foreach (ComLightDeviceEntry entry in devices)
        {
            int n = entry.Channels.Length;
            int[] raw = new int[n];
            for (int i = 0; i < n; i++)
                raw[i] = PercentToRaw255(percents[idx++]);

            commands.Add(new ComPortFlashCommand(entry.ComPort, entry.Channels, raw));
        }

        return commands;
    }

    private ComLightApplyResponse ApplyAllOffInternal()
    {
        var flashCommands = _uniqueDevices
            .Select(d => new ComPortFlashCommand(d.ComPort, d.Channels, []))
            .ToList();
        var cmdByCom = flashCommands.ToDictionary(static c => c.ComPort, static c => c, StringComparer.OrdinalIgnoreCase);
        var applied = _isolatedBank.ApplyAllOff(flashCommands);
        var results = MergeResults(applied, cmdByCom, "Off");

        bool success = EvaluateSuccess(results);
        return new ComLightApplyResponse
        {
            Success = success,
            Error = success ? null : "Не все подключённые COM выключились.",
            Results = results
        };
    }

    private List<ComLightApplyResultItem> MergeResults(
        IReadOnlyList<(string ComPort, bool Ok, string? Message)> applied,
        IReadOnlyDictionary<string, ComPortFlashCommand> cmdByCom,
        string source)
    {
        var byPort = applied.ToDictionary(static r => r.ComPort, static r => r, StringComparer.OrdinalIgnoreCase);
        var merged = new List<ComLightApplyResultItem>(_uniqueDevices.Length);

        foreach (ComLightDeviceEntry device in _uniqueDevices)
        {
            cmdByCom.TryGetValue(device.ComPort, out ComPortFlashCommand cmd);
            int[] channels = cmd.Channels.Length > 0 ? cmd.Channels : device.Channels;

            if (!_isolatedBank.IsPortReady(device.ComPort))
            {
                string detail = _isolatedBank.SkippedPorts.TryGetValue(device.ComPort, out string? reason)
                    ? reason
                    : "не подключён";
                merged.Add(new ComLightApplyResultItem
                {
                    ComPort = device.ComPort,
                    Success = false,
                    Skipped = true,
                    Error = $"пропущен: {detail}",
                    LightControllerSource = source,
                    Channels = channels
                });
                continue;
            }

            if (!byPort.TryGetValue(device.ComPort, out var row))
            {
                merged.Add(new ComLightApplyResultItem
                {
                    ComPort = device.ComPort,
                    Success = false,
                    Error = "нет ответа",
                    LightControllerSource = source,
                    Channels = channels
                });
                continue;
            }

            merged.Add(new ComLightApplyResultItem
            {
                ComPort = device.ComPort,
                Success = row.Ok,
                Message = row.Ok ? row.Message : null,
                Error = row.Ok ? null : row.Message,
                LightControllerSource = source,
                Channels = channels,
                Brightness = source == "On" && cmd.Brightness.Length > 0 ? cmd.Brightness : null
            });
        }

        return merged;
    }

    private static bool EvaluateSuccess(IReadOnlyList<ComLightApplyResultItem> results) =>
        results.Where(static r => !r.Skipped).All(static r => r.Success)
        && results.Any(static r => !r.Skipped);

    private static string BuildOnMessage(IReadOnlyList<ComLightApplyResultItem> results, string appliedCsv)
    {
        var skipped = results.Where(static r => r.Skipped).Select(static r => r.ComPort).ToList();
        string baseMsg = $"Подключённые COM включены, яркость %: {appliedCsv}.";
        return skipped.Count == 0
            ? baseMsg
            : $"{baseMsg} Пропущены: {string.Join(", ", skipped)}.";
    }

    private static string BuildOffMessage(IReadOnlyList<ComLightApplyResultItem> results)
    {
        var skipped = results.Where(static r => r.Skipped).Select(static r => r.ComPort).ToList();
        return skipped.Count == 0
            ? "Все подключённые COM выключены."
            : $"Подключённые COM выключены. Пропущены: {string.Join(", ", skipped)}.";
    }

    /// <summary>Проценты 0–100; одно значение — на все каналы; N значений — по порядку подключённых устройств из конфига.</summary>
    private static int[] ParseBrightnessPercents(string? csv, int channelCount)
    {
        if (channelCount <= 0)
            return [];

        if (string.IsNullOrWhiteSpace(csv))
            return Enumerable.Repeat(100, channelCount).ToArray();

        string[] parts = csv.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (parts.Length == 0)
            return Enumerable.Repeat(100, channelCount).ToArray();

        var values = new List<int>(parts.Length);
        foreach (string part in parts)
        {
            if (!int.TryParse(part, NumberStyles.Integer, CultureInfo.InvariantCulture, out int p))
                throw new FormatException($"Неверное значение яркости: '{part}' (ожидаются числа 0–100 через запятую).");
            values.Add(Math.Clamp(p, 0, 100));
        }

        if (values.Count == 1)
            return Enumerable.Repeat(values[0], channelCount).ToArray();

        if (values.Count == channelCount)
            return values.ToArray();

        if (values.Count < channelCount)
        {
            int last = values[^1];
            while (values.Count < channelCount)
                values.Add(last);
            return values.ToArray();
        }

        return values.Take(channelCount).ToArray();
    }

    private static int PercentToRaw255(int percent) =>
        (int)Math.Round(Math.Clamp(percent, 0, 100) * 255.0 / 100.0);

    public static ComLightDeviceEntry[] DeduplicateDevicesForOptions(ComLightDeviceEntry[] devices) =>
        DeduplicateDevices(devices);

    private static ComLightDeviceEntry[] DeduplicateDevices(ComLightDeviceEntry[] devices)
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var list = new List<ComLightDeviceEntry>();
        foreach (ComLightDeviceEntry d in devices)
        {
            string com = d.ComPort?.Trim() ?? "";
            if (com.Length == 0 || !seen.Add(com))
                continue;
            list.Add(new ComLightDeviceEntry
            {
                DeviceId = d.DeviceId,
                ComPort = NormalizeComPort(com),
                Channels = NormalizeChannels(d.Channels),
                TimerArmSource = d.TimerArmSource
            });
        }

        return list.OrderBy(static d => d.ComPort, StringComparer.OrdinalIgnoreCase).ToArray();
    }

    private static int[] NormalizeChannels(int[] channels)
    {
        var unique = new List<int>(4);
        if (channels != null)
        {
            foreach (int ch in channels)
            {
                if (ch is >= 1 and <= 4 && !unique.Contains(ch))
                    unique.Add(ch);
            }
        }

        return unique.ToArray();
    }

    private static string NormalizeComPort(string raw) => MvsComPortEnumerator.NormalizeComPort(raw);
}
