using System.Runtime.InteropServices;

namespace IoInputMonitor;

/// <summary>
/// Software trigger таймера IO box (кнопка Execute в MVS).
/// Ищем любой подходящий экспорт в DLL.
/// </summary>
internal static class MvIoTimerTrigger
{
    private static int _initialized;
    private static Func<IntPtr, uint, int>? _triggerByIndex;
    private static string? _resolvedExport;

    public static bool IsAvailable => EnsureInitialized() && _triggerByIndex != null;

    public static string? ResolvedExport => _resolvedExport;

    public static int TriggerSoftware(IntPtr handle, int timerIndex, out string detail)
    {
        if (timerIndex is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(timerIndex), "timer_index must be 1..8.");

        if (!EnsureInitialized() || _triggerByIndex == null)
        {
            detail = "MV_IO timer trigger export not found in MvIOInterfaceBox.dll";
            return unchecked((int)0x80000004);
        }

        int[] candidates = [timerIndex, timerIndex - 1];
        int lastRet = unchecked((int)0x80000004);
        foreach (int candidate in candidates.Distinct())
        {
            if (candidate < 0)
                continue;

            lastRet = _triggerByIndex(handle, (uint)candidate);
            if (lastRet == MvIoNative.MvOk)
            {
                detail = $"{_resolvedExport}(timer={candidate})";
                return lastRet;
            }
        }

        detail = $"{_resolvedExport}(timer={timerIndex})";
        return lastRet;
    }

    private static bool EnsureInitialized()
    {
        if (Volatile.Read(ref _initialized) == 1)
            return true;

        lock (typeof(MvIoTimerTrigger))
        {
            if (_initialized == 1)
                return true;

            TryResolveTriggerExport();
            _initialized = 1;
            return true;
        }
    }

    private static void TryResolveTriggerExport()
    {
        if (!NativeLibrary.TryLoad("MvIOInterfaceBox.dll", typeof(MvIoNative).Assembly, null, out IntPtr module))
            return;

        var candidates = new List<string>(CandidateExports);
        foreach (string export in MvIoDllExports.ListExports())
        {
            if (export.Contains("Timer", StringComparison.OrdinalIgnoreCase) &&
                (export.Contains("Trigger", StringComparison.OrdinalIgnoreCase) ||
                 export.Contains("Execute", StringComparison.OrdinalIgnoreCase) ||
                 export.Contains("Software", StringComparison.OrdinalIgnoreCase)))
            {
                if (!candidates.Contains(export, StringComparer.Ordinal))
                    candidates.Add(export);
            }
        }

        foreach (string export in candidates)
        {
            if (!NativeLibrary.TryGetExport(module, export, out IntPtr address))
                continue;

            try
            {
                _triggerByIndex = Marshal.GetDelegateForFunctionPointer<TriggerByIndexDelegate>(address);
                _resolvedExport = export;
                return;
            }
            catch (MarshalDirectiveException)
            {
                // wrong signature
            }
        }
    }

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int TriggerByIndexDelegate(IntPtr handle, uint timerIndex);

    private static readonly string[] CandidateExports =
    [
        "MV_IO_TriggerTimer",
        "MV_IO_ExecuteTimer",
        "MV_IO_TimerSoftwareTrigger",
        "MV_IO_TriggerTimerSoftware",
        "MV_IO_SetTimerSoftwareTrigger",
        "MV_IO_ExecuteTimerSoftware",
        "MV_IO_SetTimerTrigger",
        "MV_IO_TimerTrigger",
        "MV_IO_SoftTriggerTimer",
        "MV_IO_TriggerSoftware"
    ];
}
