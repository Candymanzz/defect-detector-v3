namespace LightServer.Models;

/// <summary>
/// POST /api/com/light — одним запросом все COM из appsettings.
/// <para>state: on | off</para>
/// <para>brightness: яркость вспышек в % через запятую (0–100). Пример: "100" или "100,80,90,100,100,100,100,100,100,100".
/// Порядок: COM1 (2 канала), COM2 (4), COM3 (4). Пусто при on → 100% на всех.</para>
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
