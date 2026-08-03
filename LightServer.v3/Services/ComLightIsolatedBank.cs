using System.Collections.Concurrent;
using System.Diagnostics;
using LightServer;
using LightServer.Models;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using MvCameraControl;

namespace LightServer.Services;

/// <summary>Банк COM: по одному изолированному объекту на порт, без общего EnumDevices(COM1+COM2+COM3).</summary>
public sealed class ComLightIsolatedBank : IDisposable
{
    private readonly IReadOnlyDictionary<string, IsolatedComPortLight> _ports;
    private readonly SerialLightOptions _serialOptions;
    private readonly ILogger<ComLightIsolatedBank> _log;
    private readonly object _initLock = new();
    private readonly HashSet<string> _readyPorts = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, string> _skippedPorts = new(StringComparer.OrdinalIgnoreCase);
    private bool _initialized;
    private string? _initError;
    private List<IDeviceInfo>? _bankDeviceList;

    public ComLightIsolatedBank(
        IOptions<ComLightDevicesOptions> devices,
        IOptions<SerialLightOptions> serial,
        ILoggerFactory loggerFactory)
    {
        _serialOptions = serial.Value;
        _log = loggerFactory.CreateLogger<ComLightIsolatedBank>();
        ComLightDeviceEntry[] unique = ComLightBankService.DeduplicateDevicesForOptions(devices.Value.Devices);
        var map = new Dictionary<string, IsolatedComPortLight>(StringComparer.OrdinalIgnoreCase);
        foreach (ComLightDeviceEntry entry in unique)
        {
            string com = MvsComPortEnumerator.NormalizeComPort(entry.ComPort);
            if (com.Length == 0 || map.ContainsKey(com))
                continue;

            map[com] = new IsolatedComPortLight(
                com,
                entry.Channels,
                entry.TimerArmSource,
                serial,
                loggerFactory.CreateLogger<IsolatedComPortLight>());
        }

        _ports = map;
    }

    public IReadOnlyCollection<string> ConfiguredComPorts => _ports.Keys.ToList();

    public IReadOnlyCollection<string> ReadyComPorts => _readyPorts.ToList();

    public IReadOnlyDictionary<string, string> SkippedPorts => _skippedPorts;

    public bool IsInitialized => _initialized;

    public bool IsPartial => _initialized && _skippedPorts.Count > 0;

    public bool IsPortReady(string comPort) =>
        _readyPorts.Contains(MvsComPortEnumerator.NormalizeComPort(comPort));

    public (bool ok, string? error) InitializeAll()
    {
        lock (_initLock)
        {
            if (_initialized && _readyPorts.Count > 0)
                return (true, _initError);

            _readyPorts.Clear();
            _skippedPorts.Clear();

            if (_ports.Count == 0)
            {
                _initError = null;
                _initialized = true;
                _log.LogInformation("IsolatedBank: COM-порты не заданы — пропуск инициализации.");
                return (true, null);
            }

            var portOrder = _ports.Keys.OrderBy(static c => c, StringComparer.OrdinalIgnoreCase).ToList();
            var (enumOk, enumErr, list, comToIndex) = MvsComPortEnumerator.EnumerateBankPorts(portOrder);
            if (!enumOk || list == null)
            {
                _initError = enumErr ?? "EnumDevices failed.";
                _initialized = false;
                _log.LogError("IsolatedBank: перечисление не удалось: {Error}", _initError);
                return (false, _initError);
            }

            _bankDeviceList = list;
            _log.LogInformation(
                "IsolatedBank: EnumDevices count={Count}, map=[{Map}]",
                list.Count,
                string.Join(", ", comToIndex.Select(static kv => $"{kv.Key}:{kv.Value}")));

            string seen = string.Join("; ", list.Select(MvsComPortEnumerator.DescribeDevice));

            foreach (string com in portOrder)
            {
                if (!comToIndex.TryGetValue(com, out int idx))
                {
                    string reason = $"{com} не найден в EnumDevices. Видно: [{seen}]";
                    _skippedPorts[com] = reason;
                    _log.LogWarning("IsolatedBank: пропуск {Com}: {Reason}", com, reason);
                    continue;
                }

                var (ok, err) = _ports[com].OpenFromBankEnumeration(list, idx);
                if (!ok)
                {
                    string reason = err ?? $"{com} не открылся.";
                    _skippedPorts[com] = reason;
                    _log.LogWarning("IsolatedBank: пропуск {Com}: {Reason}", com, reason);
                    continue;
                }

                _readyPorts.Add(com);
            }

            if (_readyPorts.Count == 0)
            {
                _initError = _skippedPorts.Count > 0
                    ? string.Join("; ", _skippedPorts.Select(static kv => kv.Value))
                    : "Ни один COM не открылся.";
                _initialized = false;
                _log.LogError("IsolatedBank: {Error}", _initError);
                return (false, _initError);
            }

            _initialized = true;
            if (_skippedPorts.Count > 0)
            {
                _initError = $"Частично: готовы [{string.Join(", ", _readyPorts)}], пропущены [{string.Join(", ", _skippedPorts.Keys)}].";
                _log.LogWarning("IsolatedBank: {Summary}", _initError);
            }
            else
            {
                _initError = null;
                _log.LogInformation("IsolatedBank: готово {Ports}", string.Join(", ", _readyPorts));
            }

            return (true, _initError);
        }
    }

    public IReadOnlyList<(string ComPort, bool Ok, string? Message)> ApplyAllOn(
        IReadOnlyList<ComPortFlashCommand> commands)
    {
        if (MvLeFlashSync.IsBankDirectOnMode(_serialOptions.BankFlashMode))
            return ApplyAllOnDirect(commands);

        return ApplyAllOnTwoPhase(commands);
    }

    private IReadOnlyList<(string ComPort, bool Ok, string? Message)> ApplyAllOnDirect(
        IReadOnlyList<ComPortFlashCommand> commands)
    {
        var results = new ConcurrentDictionary<string, (bool Ok, string? Message)>(StringComparer.OrdinalIgnoreCase);
        var sw = Stopwatch.StartNew();

        Parallel.ForEach(commands, cmd =>
        {
            if (TryRecordSkipped(cmd.ComPort, results))
                return;

            if (!_ports.TryGetValue(cmd.ComPort, out IsolatedComPortLight? port))
            {
                results[cmd.ComPort] = (false, "COM не в конфиге isolated bank.");
                return;
            }

            (bool ok, string? msg) = port.ApplyDirectOn(cmd.Brightness);
            results[cmd.ComPort] = ok ? (true, msg) : (false, msg);
            if (!ok)
                _log.LogWarning("Bank on fail {ComPort}: {Msg}", cmd.ComPort, msg);
        });

        sw.Stop();
        _log.LogInformation("Bank direct-on {Count} COM in {Ms} ms (parallel)", commands.Count, sw.ElapsedMilliseconds);
        return BuildResults(commands, results);
    }

    private IReadOnlyList<(string ComPort, bool Ok, string? Message)> ApplyAllOnTwoPhase(
        IReadOnlyList<ComPortFlashCommand> commands)
    {
        var results = new ConcurrentDictionary<string, (bool Ok, string? Message)>(StringComparer.OrdinalIgnoreCase);
        var toFire = new ConcurrentBag<(ComPortFlashCommand Cmd, IsolatedComPortLight Port)>();

        Parallel.ForEach(commands, cmd =>
        {
            if (TryRecordSkipped(cmd.ComPort, results))
                return;

            if (!_ports.TryGetValue(cmd.ComPort, out IsolatedComPortLight? port))
            {
                results[cmd.ComPort] = (false, "COM не в конфиге isolated bank.");
                return;
            }

            (bool ok, string? msg) = port.PrepareFlash(cmd.Brightness);
            if (!ok)
            {
                results[cmd.ComPort] = (false, msg);
                _log.LogWarning("Bank prep fail {ComPort}: {Msg}", cmd.ComPort, msg);
                return;
            }

            toFire.Add((cmd, port));
        });

        var fireList = toFire.ToList();
        if (fireList.Count == 0)
            return BuildResults(commands, results);

        // Barrier: все COM завершили prep → одновременный software trigger (синхронная вспышка стоек).
        var sw = Stopwatch.StartNew();
        var barrier = new Barrier(fireList.Count);
        Parallel.ForEach(fireList, item =>
        {
            try
            {
                barrier.SignalAndWait();
                (bool ok, string? msg) = item.Port.FireFlash();
                results[item.Cmd.ComPort] = ok ? (true, msg) : (false, msg);
                if (!ok)
                    _log.LogWarning("Bank fire fail {ComPort}: {Msg}", item.Cmd.ComPort, msg);
            }
            catch (Exception ex)
            {
                results[item.Cmd.ComPort] = (false, ex.Message);
            }
        });

        sw.Stop();
        _log.LogInformation("Bank fire phase {Count} COM in {Ms} ms (parallel, no ParameterGate)", fireList.Count, sw.ElapsedMilliseconds);

        return BuildResults(commands, results);
    }

    public IReadOnlyList<(string ComPort, bool Ok, string? Message)> ApplyAllOff(
        IReadOnlyList<ComPortFlashCommand> commands)
    {
        var results = new ConcurrentDictionary<string, (bool Ok, string? Message)>(StringComparer.OrdinalIgnoreCase);

        Parallel.ForEach(commands, cmd =>
        {
            if (TryRecordSkipped(cmd.ComPort, results))
                return;

            if (!_ports.TryGetValue(cmd.ComPort, out IsolatedComPortLight? port))
            {
                results[cmd.ComPort] = (false, "COM не в конфиге isolated bank.");
                return;
            }

            (bool ok, string? msg) = port.ApplyOff();
            results[cmd.ComPort] = ok ? (true, msg) : (false, msg);
        });

        return BuildResults(commands, results);
    }

    /// <summary>
    /// Яркость на уже открытом COM из банка (для /camera-flash/single|pair).
    /// По умолчанию без On — иначе свет остаётся гореть вне bank/DI цикла.
    /// </summary>
    public (bool ok, string? error) ApplyChannelBrightness(
        string comPort,
        int[] updateChannels,
        int[] powers,
        bool turnOn = false)
    {
        string com = MvsComPortEnumerator.NormalizeComPort(comPort);
        if (!_initialized)
        {
            var (initOk, initErr) = InitializeAll();
            if (!initOk)
                return (false, initErr ?? "IsolatedBank не инициализирован.");
        }

        if (!_ports.TryGetValue(com, out IsolatedComPortLight? port))
            return (false, $"{com}: нет в IsolatedBank.");

        if (!_readyPorts.Contains(com))
        {
            string reason = _skippedPorts.TryGetValue(com, out string? detail)
                ? detail
                : "не готов";
            return (false, $"{com}: {reason}");
        }

        return port.ApplyChannelBrightness(updateChannels, powers, turnOn);
    }

    private bool TryRecordSkipped(
        string comPort,
        ConcurrentDictionary<string, (bool Ok, string? Message)> results)
    {
        string com = MvsComPortEnumerator.NormalizeComPort(comPort);
        if (_readyPorts.Contains(com))
            return false;

        string reason = _skippedPorts.TryGetValue(com, out string? detail)
            ? $"пропущен: {detail}"
            : "пропущен: не инициализирован";
        results[com] = (false, reason);
        return true;
    }

    private static IReadOnlyList<(string ComPort, bool Ok, string? Message)> BuildResults(
        IReadOnlyList<ComPortFlashCommand> commands,
        ConcurrentDictionary<string, (bool Ok, string? Message)> results) =>
        commands
            .Select(cmd => results.TryGetValue(cmd.ComPort, out var r)
                ? (cmd.ComPort, r.Ok, r.Message)
                : (cmd.ComPort, false, (string?)null))
            .ToList();

    public void Dispose()
    {
        foreach (IsolatedComPortLight port in _ports.Values)
            port.Dispose();
    }
}

public readonly record struct ComPortFlashCommand(string ComPort, int[] Channels, int[] Brightness);
