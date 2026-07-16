namespace LightServer.Models;

/// <summary>Камеры 1..8: две вспышки (левая/правая).</summary>
public sealed class CameraPairFlashRequest
{
    public int CameraNumber { get; set; }
    public int LeftPower { get; set; }
    public int RightPower { get; set; }
}

/// <summary>Камеры 9..10: одна вспышка (один канал).</summary>
public sealed class CameraSingleFlashRequest
{
    public int CameraNumber { get; set; }
    public int Power { get; set; }
}

/// <summary>
/// Одновременный On/Off всех устройств из light_hardware (Ethernet + COM) —
/// один запрос вместо N× /pair|/single. Для interval_flash.
/// </summary>
public sealed class CameraFlashBankRequest
{
    /// <summary>on | off</summary>
    public string State { get; set; } = "off";
}
