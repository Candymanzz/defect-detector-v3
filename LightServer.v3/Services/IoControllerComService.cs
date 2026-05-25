using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using LightServer.Models;
using Microsoft.Extensions.Options;

namespace LightServer.Services;

/// <summary>
/// Управление MV-LE / IO Box по COM через MvIOInterfaceBox.dll.
/// Поведение выровнено с рабочим defect-detector LightService: сессия COM не рвётся, вспышки — Trigger.
/// </summary>
public sealed class IoControllerComService : IDisposable
{
    private readonly IoControllerOptions _ioOptions;
    private readonly ConcurrentDictionary<string, ComSession> _sessions = new(StringComparer.OrdinalIgnoreCase);
    private readonly object _sessionLock = new();
    private bool _disposed;

    public IoControllerComService(IOptions<IoControllerOptions> ioOptions) =>
        _ioOptions = ioOptions.Value;

    public (bool ok, string? error) SetLight(LightCommandRequestCom request)
    {
        if (string.IsNullOrWhiteSpace(request.ComPort))
            return (false, "comPort обязателен для прямого COM-режима.");

        string comPort = request.ComPort.Trim();
        int[] channels = request.Channels is { Length: > 0 } ? request.Channels : [1, 2, 3, 4];
        string source = string.IsNullOrWhiteSpace(request.LightControllerSource) ? "On" : request.LightControllerSource.Trim();
        bool off = source.Equals("Off", StringComparison.OrdinalIgnoreCase);
        bool triggerMode = !off && IsTriggerFlashMode();
        int durationMs = Math.Clamp(_ioOptions.DefaultDurationMs, 1, 5000);
        string mapping = (_ioOptions.BrightnessMapping ?? "Scale255To100").Trim();
        string sessionKey = NormalizeComKey(comPort);

        lock (_sessionLock)
        {
            if (_disposed)
                return (false, "IoControllerComService остановлен.");

            try
            {
                var (session, openErr) = GetOrOpenSession(comPort);
                if (session == null)
                    return (false, openErr);

                var applied = new List<string>(channels.Length);
                IntPtr handle = session.Handle;
                var channelParams = new Native.MV_IO_LIGHT_PARAM[channels.Length];

                for (int i = 0; i < channels.Length; i++)
                {
                    int ch = channels[i];
                    if (ch is < 1 or > 4)
                        return (false, $"Invalid channel {ch}. Use 1..4.");

                    int rawBrightness = request.Brightness is { Length: > 0 } && i < request.Brightness.Length
                        ? request.Brightness[i]
                        : off ? 0 : 255;
                    ushort lightValue = ToMvIoLightValue(rawBrightness, off, mapping, out string note);

                    byte portIndex = (byte)(ch - 1);
                    channelParams[i] = BuildLightParam(portIndex, lightValue, off, triggerMode, durationMs);

                    string modeTag = off ? "off" : triggerMode ? $"trigger {durationMs}ms" : "hold";
                    applied.Add($"ch{ch}={note}, {modeTag}");
                }

                for (int i = 0; i < channels.Length; i++)
                {
                    int ch = channels[i];
                    int ret = Native.MV_IO_SetLightParam(handle, ref channelParams[i]);
                    if (ret != 0)
                        return (false, $"MV_IO_SetLightParam failed on ch{ch}: 0x{ret:x8}");
                }

                if (triggerMode && !off)
                    Thread.Sleep(durationMs);

                if (!_ioOptions.KeepComSessionOpen)
                    CloseSession(sessionKey);

                string flashMode = _ioOptions.FlashMode ?? "Trigger";
                return (true,
                    $"IO box {session.OpenedName}, FlashMode={flashMode}, mapping={mapping}: {string.Join("; ", applied)}. " +
                    (_ioOptions.KeepComSessionOpen ? "COM-сессия оставлена открытой." : ""));
            }
            catch (DllNotFoundException)
            {
                return (false, "MvIOInterfaceBox.dll not found. Скопируйте DLL из MV IO SDK (win64) рядом с LightServer.exe.");
            }
            catch (Exception ex)
            {
                return (false, ex.Message);
            }
        }
    }

    private bool IsTriggerFlashMode() =>
        string.Equals(_ioOptions.FlashMode, "Trigger", StringComparison.OrdinalIgnoreCase);

    private static Native.MV_IO_LIGHT_PARAM BuildLightParam(
        byte portIndex, ushort lightValue, bool off, bool triggerMode, int durationMs)
    {
        if (off)
        {
            return new Native.MV_IO_LIGHT_PARAM
            {
                nPortNumber = portIndex,
                nLightValue = 0,
                nLightState = (ushort)Native.LightState.On,
                nLightEdge = 0,
                nDurationTime = 0,
                nReserved = new uint[3]
            };
        }

        if (triggerMode)
        {
            return new Native.MV_IO_LIGHT_PARAM
            {
                nPortNumber = portIndex,
                nLightValue = lightValue,
                nLightState = (ushort)Native.LightState.Trigger,
                nLightEdge = (ushort)Native.LightEdge.Rising,
                nDurationTime = (ushort)Math.Clamp(durationMs, 1, 5000),
                nReserved = new uint[3]
            };
        }

        return new Native.MV_IO_LIGHT_PARAM
        {
            nPortNumber = portIndex,
            nLightValue = lightValue,
            nLightState = (ushort)Native.LightState.On,
            nLightEdge = 0,
            nDurationTime = 0,
            nReserved = new uint[3]
        };
    }

    private void CloseSession(string comKey)
    {
        if (_sessions.TryRemove(comKey, out ComSession? session))
            session.Dispose();
    }

    private (ComSession? session, string? error) GetOrOpenSession(string comPort)
    {
        string key = NormalizeComKey(comPort);
        if (_sessions.TryGetValue(key, out ComSession? existing) && existing.Handle != IntPtr.Zero)
            return (existing, null);

        IntPtr handle = IntPtr.Zero;
        int ret = Native.MV_IO_CreateHandle(ref handle);
        if (ret != 0 || handle == IntPtr.Zero)
            return (null, $"MV_IO_CreateHandle failed: 0x{ret:x8}");

        var tried = new List<string>();
        string? openedName = null;
        foreach (string candidate in BuildComCandidates(comPort))
        {
            var serial = new Native.MV_IO_SERIAL
            {
                strComName = candidate,
                nReserved = new uint[8]
            };
            ret = Native.MV_IO_Open(handle, ref serial);
            tried.Add($"{candidate}=0x{ret:x8}");
            if (ret == 0)
            {
                openedName = candidate;
                break;
            }
        }

        if (openedName == null)
        {
            Native.MV_IO_DestroyHandle(handle);
            return (null, $"MV_IO_Open failed. tried: {string.Join(", ", tried)}");
        }

        var session = new ComSession(key, handle, openedName);
        _sessions[key] = session;
        return (session, null);
    }

    public static IReadOnlyList<string> GetHostComPorts() =>
        System.IO.Ports.SerialPort.GetPortNames()
            .OrderBy(static p => p, StringComparer.OrdinalIgnoreCase)
            .ToArray();

    private static string NormalizeComKey(string rawCom)
    {
        string upper = rawCom.Trim().ToUpperInvariant();
        return upper.StartsWith("COM", StringComparison.Ordinal) ? upper : $"COM{upper}";
    }

    private static IEnumerable<string> BuildComCandidates(string rawCom)
    {
        string trimmed = rawCom.Trim();
        string upper = trimmed.ToUpperInvariant();
        string canonical = upper.StartsWith("COM", StringComparison.Ordinal) ? upper : $"COM{upper}";

        yield return canonical;
        if (!string.Equals(trimmed, canonical, StringComparison.Ordinal))
            yield return trimmed;
    }

    private static ushort ToMvIoLightValue(int rawInput, bool off, string mapping, out string note)
    {
        if (off)
        {
            note = "nLightValue=0";
            return 0;
        }

        string m = mapping.ToLowerInvariant();
        return m switch
        {
            "percent" or "percent0to100" or "percent0_100" => PercentNote(rawInput, out note),
            "raw255" or "raw" => RawNote(rawInput, out note),
            _ => Scale255Note(rawInput, out note)
        };
    }

    private static ushort Scale255Note(int rawInput, out string note)
    {
        int raw = Math.Clamp(rawInput, 0, 255);
        int p = (int)Math.Round(raw * 100.0 / 255.0);
        p = Math.Clamp(p, 0, 100);
        note = $"nLightValue={p} (~{p}%, raw={raw})";
        return (ushort)p;
    }

    private static ushort PercentNote(int rawInput, out string note)
    {
        int p = Math.Clamp(rawInput, 0, 100);
        note = $"nLightValue={p} ({p}%)";
        return (ushort)p;
    }

    private static ushort RawNote(int rawInput, out string note)
    {
        int r = Math.Clamp(rawInput, 0, 255);
        note = $"nLightValue={r}";
        return (ushort)r;
    }

    public void Dispose()
    {
        lock (_sessionLock)
        {
            if (_disposed) return;
            _disposed = true;
            foreach (ComSession s in _sessions.Values)
                s.Dispose();
            _sessions.Clear();
        }
    }

    private sealed class ComSession : IDisposable
    {
        public string Key { get; }
        public IntPtr Handle { get; }
        public string OpenedName { get; }

        public ComSession(string key, IntPtr handle, string openedName)
        {
            Key = key;
            Handle = handle;
            OpenedName = openedName;
        }

        public void Dispose()
        {
            if (Handle == IntPtr.Zero) return;
            try
            {
                Native.MV_IO_Close(Handle);
                Native.MV_IO_DestroyHandle(Handle);
            }
            catch
            {
                // ignore on shutdown
            }
        }
    }

    private static class Native
    {
        public enum LightState : ushort
        {
            On = 1,
            Off = 2,
            Trigger = 3
        }

        public enum LightEdge : ushort
        {
            Rising = 1
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)]
        public struct MV_IO_SERIAL
        {
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
            public string strComName;

            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
            public uint[] nReserved;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct MV_IO_LIGHT_PARAM
        {
            public byte nPortNumber;
            public ushort nLightValue;
            public ushort nLightState;
            public ushort nLightEdge;
            public ushort nDurationTime;

            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 3)]
            public uint[] nReserved;
        }

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_CreateHandle")]
        public static extern int MV_IO_CreateHandle(ref IntPtr handle);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_DestroyHandle")]
        public static extern int MV_IO_DestroyHandle(IntPtr handle);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_Open", CharSet = CharSet.Ansi)]
        public static extern int MV_IO_Open(IntPtr handle, ref MV_IO_SERIAL serial);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_Close")]
        public static extern void MV_IO_Close(IntPtr handle);

        [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetLightParam")]
        public static extern int MV_IO_SetLightParam(IntPtr handle, ref MV_IO_LIGHT_PARAM lightParam);
    }
}
