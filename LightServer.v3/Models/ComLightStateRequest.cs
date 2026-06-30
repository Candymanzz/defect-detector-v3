namespace LightServer.Models;

/// <summary>
/// POST /api/com/light — одним запросом все COM из light_hardware.yaml.
/// <para>state: on | off</para>
/// <para>brightness: яркость в % через запятую (0–100). Порядок — по подключённым COM (см. GET /api/com/light → channelOrder). Одно значение — на все подключённые каналы.</para>
/// </summary>
public sealed class ComLightStateRequest
{
    public string State { get; set; } = "off";

    public string? Brightness { get; set; }
}

public sealed class ComLightStateResponse
{
    public bool Success { get; set; }
    public string? Message { get; set; }
    public string? Error { get; set; }
    public string State { get; set; } = "";
    public string? Brightness { get; set; }
    public IReadOnlyList<ComLightApplyResultItem> Results { get; set; } = Array.Empty<ComLightApplyResultItem>();
}

public sealed class ComLightApplyResultItem
{
    public string ComPort { get; set; } = "";
    public bool Success { get; set; }
    public bool Skipped { get; set; }
    public string? Message { get; set; }
    public string? Error { get; set; }
    public string LightControllerSource { get; set; } = "";
    public int[] Channels { get; set; } = [];
    public int[]? Brightness { get; set; }
}

public sealed class ComLightApplyResponse
{
    public bool Success { get; set; }
    public string? Error { get; set; }
    public IReadOnlyList<ComLightApplyResultItem> Results { get; set; } = Array.Empty<ComLightApplyResultItem>();
}
