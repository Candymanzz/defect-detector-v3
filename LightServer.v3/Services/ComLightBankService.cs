using System.Globalization;
using LightServer.Models;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace LightServer.Services;

/// <summary>Статический банк: по одной долгоживущей сессии на каждый COM из конфига.</summary>
public sealed class ComLightBankService
{
    private readonly ComLightDevicesOptions _devices;
    private readonly LightControlService _light;
    private readonly ILogger<ComLightBankService> _log;
    private readonly object _initLock = new();
    private bool _initialized;
    private string? _initError;

    private readonly ComLightDeviceEntry[] _uniqueDevices;

    public ComLightBankService(
        IOptions<ComLightDevicesOptions> devices,
        LightControlService light,
        ILogger<ComLightBankService> log)
    {
        _devices = devices.Value;
        _uniqueDevices = DeduplicateDevices(_devices.Devices);
        _light = light;
        _log = log;
    }

    public bool IsInitialized => _initialized;

    public IReadOnlyList<ComLightDeviceEntry> ConfiguredDevices => _uniqueDevices;

    public (bool ok, string? error) EnsureInitialized()
    {
        if (_initialized)
            return string.IsNullOrEmpty(_initError) ? (true, null) : (false, _initError);

        lock (_initLock)
        {
            if (_initialized)
                return string.IsNullOrEmpty(_initError) ? (true, null) : (false, _initError);

            if (_uniqueDevices.Length == 0)
            {
                _initError = "ComLightDevices:Devices пуст — задайте COM1/COM2/COM3 в appsettings.json.";
                _initialized = false;
                return (false, _initError);
            }

            var (ok, err) = _light.InitializeComBank(_uniqueDevices);
            _initError = err;
            _initialized = ok;
            if (ok)
                _log.LogInformation("ComLightBank: открыто {Count} COM ({Ports})",
                    _uniqueDevices.Length,
                    string.Join(", ", _uniqueDevices.Select(static d => d.ComPort)));
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
                Message = off.Success ? "Все COM выключены." : null,
                Error = off.Error,
                State = "off",
                Results = off.Results
            };
        }

        int totalChannels = _uniqueDevices.Sum(static d => d.Channels.Length);
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

        List<LightControlService.ComPortFlashApply> flashCommands = BuildFlashCommands(percents);
        var cmdByCom = flashCommands.ToDictionary(static c => c.ComPort, static c => c, StringComparer.OrdinalIgnoreCase);
        var applied = _light.ApplyBankOnSimultaneous(flashCommands);
        var results = applied.Select(r =>
        {
            cmdByCom.TryGetValue(r.ComPort, out LightControlService.ComPortFlashApply cmd);
            return new ComLightApplyResultItem
            {
                ComPort = r.ComPort,
                Success = r.Ok,
                Message = r.Ok ? r.Message : null,
                Error = r.Ok ? null : r.Message,
                LightControllerSource = "On",
                Channels = cmd.Channels,
                Brightness = cmd.Brightness
            };
        }).ToList();

        bool allOk = results.All(static r => r.Success);
        string appliedCsv = string.Join(",", percents);
        return new ComLightStateResponse
        {
            Success = allOk,
            Message = allOk ? $"Все COM включены одновременно, яркость %: {appliedCsv}." : null,
            Error = allOk ? null : "Часть COM не включилась.",
            State = "on",
            Brightness = appliedCsv,
            Results = results
        };
    }

    private List<LightControlService.ComPortFlashApply> BuildFlashCommands(int[] percents)
    {
        var commands = new List<LightControlService.ComPortFlashApply>(_uniqueDevices.Length);
        int idx = 0;
        foreach (ComLightDeviceEntry entry in _uniqueDevices)
        {
            int n = entry.Channels.Length;
            int[] raw = new int[n];
            for (int i = 0; i < n; i++)
                raw[i] = PercentToRaw255(percents[idx++]);

            commands.Add(new LightControlService.ComPortFlashApply(entry.ComPort, entry.Channels, raw));
        }

        return commands;
    }

    private ComLightApplyResponse ApplyAllOffInternal()
    {
        var flashCommands = _uniqueDevices
            .Select(d => new LightControlService.ComPortFlashApply(d.ComPort, d.Channels, []))
            .ToList();
        var cmdByCom = flashCommands.ToDictionary(static c => c.ComPort, static c => c, StringComparer.OrdinalIgnoreCase);
        var applied = _light.ApplyBankOffSimultaneous(flashCommands);
        var results = applied.Select(r => new ComLightApplyResultItem
        {
            ComPort = r.ComPort,
            Success = r.Ok,
            Message = r.Ok ? r.Message : null,
            Error = r.Ok ? null : r.Message,
            LightControllerSource = "Off",
            Channels = cmdByCom[r.ComPort].Channels
        }).ToList();

        bool allOk = results.All(static r => r.Success);
        return new ComLightApplyResponse
        {
            Success = allOk,
            Error = allOk ? null : "Не все COM выключились.",
            Results = results
        };
    }

    /// <summary>Проценты 0–100; одно значение — на все каналы; N значений — по порядку COM1→COM3.</summary>
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
                ComPort = NormalizeComPort(com),
                Channels = NormalizeChannels(d.Channels, com)
            });
        }

        return list.OrderBy(static d => d.ComPort, StringComparer.OrdinalIgnoreCase).ToArray();
    }

    /// <summary>Уникальные каналы 1–4; fallback по COM.</summary>
    private static int[] NormalizeChannels(int[] channels, string comPort)
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

        if (unique.Count > 0)
            return unique.ToArray();

        return string.Equals(comPort, "COM1", StringComparison.OrdinalIgnoreCase)
            ? [1, 2]
            : [1, 2, 3, 4];
    }

    private static string NormalizeComPort(string raw)
    {
        string p = raw.Trim();
        if (p.StartsWith("COM", StringComparison.OrdinalIgnoreCase))
        {
            string tail = p.Length > 3 ? p[3..] : "";
            if (int.TryParse(tail, NumberStyles.Integer, CultureInfo.InvariantCulture, out int n) && n > 0)
                return "COM" + n;
        }

        if (int.TryParse(p, NumberStyles.Integer, CultureInfo.InvariantCulture, out int num) && num > 0)
            return "COM" + num;

        return p.ToUpperInvariant();
    }
}
