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
    private readonly ILogger<ComLightIsolatedBank> _log;
    private readonly object _initLock = new();
    private bool _initialized;
    private string? _initError;
    private List<IDeviceInfo>? _bankDeviceList;

    public ComLightIsolatedBank(
        IOptions<ComLightDevicesOptions> devices,
        IOptions<SerialLightOptions> serial,
        ILoggerFactory loggerFactory)
    {
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

    public bool IsInitialized => _initialized;

    public (bool ok, string? error) InitializeAll()
    {
        lock (_initLock)
        {
            if (_initialized && string.IsNullOrEmpty(_initError))
                return (true, null);

            if (_ports.Count == 0)
            {
                _initError = "ComLightDevices:Devices пуст.";
                _initialized = false;
                return (false, _initError);
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

            foreach (string com in portOrder)
            {
                if (!comToIndex.TryGetValue(com, out int idx))
                {
                    string seen = string.Join("; ", list.Select(MvsComPortEnumerator.DescribeDevice));
                    _initError = $"{com} не найден в EnumDevices. Видно: [{seen}]";
                    _initialized = false;
                    _log.LogError("IsolatedBank: {Error}", _initError);
                    return (false, _initError);
                }

                var (ok, err) = _ports[com].OpenFromBankEnumeration(list, idx);
                if (!ok)
                {
                    _initError = err;
                    _initialized = false;
                    _log.LogError("IsolatedBank: {Com} не открылся: {Error}", com, err);
                    return (false, err);
                }

            }

            _initError = null;
            _initialized = true;
            _log.LogInformation("IsolatedBank: готово {Ports}", string.Join(", ", _ports.Keys));
            return (true, null);
        }
    }

    public IReadOnlyList<(string ComPort, bool Ok, string? Message)> ApplyAllOn(
        IReadOnlyList<ComPortFlashCommand> commands)
    {
        var results = new ConcurrentDictionary<string, (bool Ok, string? Message)>(StringComparer.OrdinalIgnoreCase);
        var toFire = new ConcurrentBag<(ComPortFlashCommand Cmd, IsolatedComPortLight Port)>();

        Parallel.ForEach(commands, cmd =>
        {
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
