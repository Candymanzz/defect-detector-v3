using System.Runtime.InteropServices;

namespace IoInputMonitor;

internal enum IoCaptureOutputMode
{
    Direct,
    Timer
}

/// <summary>
/// P/Invoke к MvIOInterfaceBox.dll (Hikrobot MV IO Box).
/// Порты DI — битовые маски: DI1=0x01, DI2=0x02, … DI8=0x80 (см. IoPortNumber).
/// </summary>
internal static class MvIoNative
{
    public const int MvOk = 0;

    public enum IoPortNumber : byte
    {
        // SDK использует битовую маску, а не номер 1..8 напрямую.
        Port1 = 0x1,
        Port2 = 0x2,
        Port3 = 0x4,
        Port4 = 0x8,
        Port5 = 0x10,
        Port6 = 0x20,
        Port7 = 0x40,
        Port8 = 0x80,
    }

    public enum IoLevel : byte
    {
        Low = 0,
        High = 1,
    }

    public enum IoEdgeType : uint
    {
        Unknown = 0,
        Rising = 1,
        Falling = 2,
    }

    public enum IoOutputPattern : uint
    {
        Single = 0,
        Pwm = 1,
    }

    public enum IoOutputEnableType : uint
    {
        Start = 0,
        End = 1,
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct MvIoSetInput
    {
        public uint Port;
        public uint Enable;
        public uint Edge;
        public uint DelayTime;
        public uint Glitch;

        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
        public uint[] Reserved;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)]
    public struct MvIoSerial
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
        public string ComName;

        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
        public uint[] Reserved;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct MvIoInputLevel
    {
        public byte PortMask;
        public byte Level0;
        public byte Level1;
        public byte Level2;
        public byte Level3;
        public byte Level4;
        public byte Level5;
        public byte Level6;
        public byte Level7;

        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
        public uint[] Reserved;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct MvIoInputEdge
    {
        public byte PortNumber;
        public ushort TriggerTimes;
        public IoEdgeType EdgeType;

        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
        public uint[] Reserved;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct MvIoSetOutput
    {
        public uint Port;
        public uint Pattern;
        public uint PulseWidth;
        public uint PulsePeriod;
        public uint PulseDuration;
        public uint Level;

        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
        public uint[] Reserved;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct MvIoOutputEnable
    {
        public uint Port;
        public uint Enable;

        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
        public uint[] Reserved;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct MvIoVersion
    {
        public uint Main;
        public uint Sub;
        public uint Modify;
        public uint Year;
        public uint Month;
        public uint Day;

        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
        public uint[] Reserved;
    }

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    public delegate void EdgeDetectionCallback(IntPtr handle, ref MvIoInputEdge edge, IntPtr user);

    [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_CreateHandle")]
    public static extern int CreateHandle(ref IntPtr handle);

    [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_DestroyHandle")]
    public static extern int DestroyHandle(IntPtr handle);

    [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_Open", CharSet = CharSet.Ansi)]
    public static extern int Open(IntPtr handle, ref MvIoSerial serial);

    [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_Close")]
    public static extern void Close(IntPtr handle);

    [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetSDKVersion")]
    public static extern int GetSdkVersion(ref MvIoVersion version);

    [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetFirmwareVersion")]
    public static extern int GetFirmwareVersion(IntPtr handle, ref MvIoVersion version);

    [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetInputLevel")]
    public static extern int GetInputLevel(IntPtr handle, ref MvIoInputLevel inputLevel);

    [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetInput")]
    public static extern int SetInput(IntPtr handle, ref MvIoSetInput input);

    [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_GetPortInputParam")]
    public static extern int GetPortInputParam(IntPtr handle, ref MvIoSetInput input);

    [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_ResetParam")]
    public static extern int ResetParam(IntPtr handle);

    [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_RegisterEdgeDetectionCallBack")]
    public static extern int RegisterEdgeDetectionCallback(IntPtr handle, EdgeDetectionCallback callback, IntPtr user);

    [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetOutput")]
    public static extern int SetOutput(IntPtr handle, ref MvIoSetOutput output);

    [DllImport("MvIOInterfaceBox.dll", EntryPoint = "MV_IO_SetOutputEnable")]
    public static extern int SetOutputEnable(IntPtr handle, ref MvIoOutputEnable enable);

    /// <summary>DO в SDK — 0-based индекс (DO5 → 4), не битовая маска как у DI.</summary>
    public static uint OutputPortIndex(int outputPort) =>
        outputPort is < 1 or > 8
            ? throw new ArgumentOutOfRangeException(nameof(outputPort), outputPort, "DO port must be 1..8.")
            : (uint)(outputPort - 1);

    public static int PortFromMask(byte portMask) =>
        portMask switch
        {
            (byte)IoPortNumber.Port1 => 1,
            (byte)IoPortNumber.Port2 => 2,
            (byte)IoPortNumber.Port3 => 3,
            (byte)IoPortNumber.Port4 => 4,
            (byte)IoPortNumber.Port5 => 5,
            (byte)IoPortNumber.Port6 => 6,
            (byte)IoPortNumber.Port7 => 7,
            (byte)IoPortNumber.Port8 => 8,
            _ => portMask
        };

    public static uint PortMaskForUint(int portNumber) => PortMaskFor(portNumber);

    public static byte PortMaskFor(int portNumber) =>
        portNumber switch
        {
            1 => (byte)IoPortNumber.Port1,
            2 => (byte)IoPortNumber.Port2,
            3 => (byte)IoPortNumber.Port3,
            4 => (byte)IoPortNumber.Port4,
            5 => (byte)IoPortNumber.Port5,
            6 => (byte)IoPortNumber.Port6,
            7 => (byte)IoPortNumber.Port7,
            8 => (byte)IoPortNumber.Port8,
            _ => throw new ArgumentOutOfRangeException(nameof(portNumber), portNumber, "DI port must be 1..8.")
        };

    // GetInputLevel возвращает Level0..Level7 — это DI1..DI8, не «уровни 0..7».
    public static byte ReadLevel(in MvIoInputLevel levels, int portNumber) =>
        portNumber switch
        {
            1 => levels.Level0,
            2 => levels.Level1,
            3 => levels.Level2,
            4 => levels.Level3,
            5 => levels.Level4,
            6 => levels.Level5,
            7 => levels.Level6,
            8 => levels.Level7,
            _ => throw new ArgumentOutOfRangeException(nameof(portNumber), portNumber, "DI port must be 1..8.")
        };
}
