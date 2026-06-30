namespace LightServer;

/// <summary>Конфигурация вспышек из config/blocks/51-light-hardware.yaml.</summary>
public sealed class LightHardwareOptions
{
    /// <summary>Один раз при старте открыть все COM-устройства и держать сессии.</summary>
    public bool InitializeOnStartup { get; set; } = true;

    public LightHardwareDeviceEntry[] Devices { get; set; } = [];

    public LightCameraRouteEntry[] CameraRoutes { get; set; } = [];
}

public sealed class LightHardwareDeviceEntry
{
    public string Id { get; set; } = "";

    public bool Enabled { get; set; } = true;

    /// <summary>com | ethernet</summary>
    public string Type { get; set; } = "com";

    public string ComPort { get; set; } = "";

    public string? Ip { get; set; }

    public int[] Channels { get; set; } = [];

    public bool IsCom => Type.Equals("com", StringComparison.OrdinalIgnoreCase);

    public bool IsEthernet =>
        Type.Equals("ethernet", StringComparison.OrdinalIgnoreCase)
        || Type.Equals("lan", StringComparison.OrdinalIgnoreCase)
        || Type.Equals("network", StringComparison.OrdinalIgnoreCase);
}

public sealed class LightCameraRouteEntry
{
    public int CameraNumber { get; set; }

    public string DeviceId { get; set; } = "";

    public int[] Channels { get; set; } = [];
}
