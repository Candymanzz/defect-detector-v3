namespace LightServer;

/// <summary>Статическая конфигурация COM-контроллеров подсветки (appsettings: ComLightDevices).</summary>
public sealed class ComLightDevicesOptions
{
    public const string SectionName = "ComLightDevices";

    /// <summary>Один раз при старте открыть все устройства и держать сессии.</summary>
    public bool InitializeOnStartup { get; set; } = true;

    /// <summary>Только из appsettings (без default — иначе .NET склеивает 3+3=6 COM).</summary>
    public ComLightDeviceEntry[] Devices { get; set; } = [];
}

public sealed class ComLightDeviceEntry
{
    public string ComPort { get; set; } = "";

    /// <summary>Только из JSON (без default — иначе [1,2] превращается в [1,2,3,4,1,2]).</summary>
    public int[] Channels { get; set; } = [];

    /// <summary>Arm для вспышки: Timer1, Timer5… Пусто — из enum устройства (Timer1 или первый Timer*).</summary>
    public string? TimerArmSource { get; set; }
}
