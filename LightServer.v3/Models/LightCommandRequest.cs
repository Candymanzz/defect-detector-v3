namespace LightServer.Models;

/// <summary>Команда MV-LE по индексу из GET /api/devices (сеть и т.п.).</summary>
public sealed class LightCommandRequest
{
    public int DeviceIndex { get; set; }

    public string LightControllerSource { get; set; } = "On";

    public int[] Channels { get; set; } = [1, 2, 3, 4];

    /// <summary>0–255 на канал; длина = channels. При On без массива — 255.</summary>
    public int[]? Brightness { get; set; }
}
