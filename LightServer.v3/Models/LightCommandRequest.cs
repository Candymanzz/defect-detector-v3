namespace LightServer.Models;

/// <summary>Команда MV-LE по индексу из GET /api/devices (сеть и т.п.).</summary>
public sealed class LightCommandRequest
{
    /// <summary>Legacy-выбор устройства по индексу из GET /api/devices.</summary>
    public int? DeviceIndex { get; set; }

    /// <summary>Предпочтительный стабильный выбор по серийному номеру.</summary>
    public string? SerialNumber { get; set; }

    /// <summary>Выбор по IPv4 для GigE-устройств (например 192.168.1.10).</summary>
    public string? IpAddress { get; set; }

    /// <summary>Выбор по имени модели (лучше вместе с serialNumber или ipAddress).</summary>
    public string? ModelName { get; set; }

    public string LightControllerSource { get; set; } = "On";

    public int[] Channels { get; set; } = [1, 2, 3, 4];

    /// <summary>0–255 на канал; длина = channels. При On без массива — 255.</summary>
    public int[]? Brightness { get; set; }
}
