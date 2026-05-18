namespace LightServer.Models;

/// <summary>Команда контроллеру подсветки MV-LE.</summary>
public sealed class LightCommandRequest
{
    /// <summary>Индекс из GET /api/devices (обычно 1 для MV-LE200).</summary>
    public int DeviceIndex { get; set; } = 1;

    /// <summary>On — свет вкл, Off — выкл.</summary>
    public string LightControllerSource { get; set; } = "On";

    /// <summary>Каналы 1–4.</summary>
    public int[] Channels { get; set; } = [1, 2, 3, 4];

    /// <summary>
    /// Яркость LightBrightness для каждого канала (0–255), порядок как в channels.
    /// 255 ≈ 100%. Если не задано и source=On — для всех каналов 255.
    /// </summary>
    public int[]? Brightness { get; set; }
}
