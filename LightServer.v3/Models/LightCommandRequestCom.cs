namespace LightServer.Models;

/// <summary>Команда MV-LE на COM (после SetEnumSerialPorts). Выбор устройства только по comPort.</summary>
public sealed class LightCommandRequestCom
{
    /// <summary>Например COM1 (как в MVS Client).</summary>
    public string? ComPort { get; set; }

    public string LightControllerSource { get; set; } = "On";

    public int[] Channels { get; set; } = [1, 2, 3, 4];

    public int[]? Brightness { get; set; }
}
