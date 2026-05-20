namespace LightServer;

/// <summary>Порты для SetEnumSerialPorts перед перечислением COM-устройств (appsettings: SerialLight:EnumPorts).</summary>
public sealed class SerialLightOptions
{
    public const string SectionName = "SerialLight";

    /// <summary>Например COM1, COM3. Используется по умолчанию для GET /api/com/devices.</summary>
    public string[] EnumPorts { get; set; } = ["COM1", "COM3"];
}
