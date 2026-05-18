namespace LightServer.Models;

public sealed class DeviceInfoDto
{
    public int Index { get; set; }
    public string TLayerType { get; set; } = "";
    public string ModelName { get; set; } = "";
    public string SerialNumber { get; set; } = "";
}

public sealed class DeviceListResponse
{
    public int Count { get; set; }
    public IReadOnlyList<DeviceInfoDto> Devices { get; set; } = Array.Empty<DeviceInfoDto>();
}

public sealed class LightCommandResponse
{
    public bool Success { get; set; }
    public string? Message { get; set; }
    public string? Error { get; set; }
    public int DeviceIndex { get; set; }
    public string LightControllerSource { get; set; } = "";
    public int[] Channels { get; set; } = [];
    public int[]? Brightness { get; set; }
}
