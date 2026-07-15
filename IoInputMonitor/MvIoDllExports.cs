using System.Runtime.InteropServices;

namespace IoInputMonitor;

/// <summary>Сканирует экспорты MvIOInterfaceBox.dll (для Timer/Output API).</summary>
internal static class MvIoDllExports
{
    private static string[]? _cached;

    public static IReadOnlyList<string> ListExports()
    {
        if (_cached != null)
            return _cached;

        if (!NativeLibrary.TryLoad("MvIOInterfaceBox.dll", typeof(MvIoNative).Assembly, null, out IntPtr module) ||
            module == IntPtr.Zero)
        {
            _cached = [];
            return _cached;
        }

        var names = new List<string>();
        try
        {
            if (!TryGetExportDirectory(module, out IntPtr exportDir, out int numNames, out IntPtr namesRvaBase))
            {
                _cached = [];
                return _cached;
            }

            for (int i = 0; i < numNames; i++)
            {
                int nameRva = Marshal.ReadInt32(namesRvaBase, i * 4);
                IntPtr namePtr = IntPtr.Add(module, nameRva);
                string? name = Marshal.PtrToStringAnsi(namePtr);
                if (!string.IsNullOrEmpty(name))
                    names.Add(name);
            }
        }
        catch
        {
            // PE parse can fail on some images — ignore
        }

        names.Sort(StringComparer.Ordinal);
        _cached = names.ToArray();
        return _cached;
    }

    public static IEnumerable<string> OutputOrTimerExports() =>
        ListExports().Where(static n =>
            n.Contains("Output", StringComparison.OrdinalIgnoreCase) ||
            n.Contains("Timer", StringComparison.OrdinalIgnoreCase) ||
            n.Contains("Trigger", StringComparison.OrdinalIgnoreCase));

    private static bool TryGetExportDirectory(
        IntPtr module,
        out IntPtr exportDir,
        out int numNames,
        out IntPtr namesRvaBase)
    {
        exportDir = IntPtr.Zero;
        numNames = 0;
        namesRvaBase = IntPtr.Zero;

        // DOS header e_lfanew at 0x3C
        int peOffset = Marshal.ReadInt32(module, 0x3C);
        // PE signature + COFF + optional header magic
        short magic = Marshal.ReadInt16(module, peOffset + 24);
        int exportDirOffset = magic == 0x20B
            ? peOffset + 24 + 112 // PE32+ data directory[0]
            : peOffset + 24 + 96; // PE32

        int exportRva = Marshal.ReadInt32(module, exportDirOffset);
        if (exportRva == 0)
            return false;

        exportDir = IntPtr.Add(module, exportRva);
        numNames = Marshal.ReadInt32(exportDir, 24);
        int namesRva = Marshal.ReadInt32(exportDir, 32);
        if (numNames <= 0 || namesRva == 0)
            return false;

        namesRvaBase = IntPtr.Add(module, namesRva);
        return true;
    }

    public static void LogInteresting(TextWriter writer)
    {
        var interesting = OutputOrTimerExports().ToList();
        if (interesting.Count == 0)
        {
            writer.WriteLine("MvIOInterfaceBox exports (Output/Timer/Trigger): (none found / parse failed)");
            return;
        }

        writer.WriteLine($"MvIOInterfaceBox exports (Output/Timer/Trigger): {interesting.Count}");
        foreach (string name in interesting)
            writer.WriteLine($"  {name}");
    }
}
