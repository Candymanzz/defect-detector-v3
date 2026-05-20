namespace LightServer;

/// <summary>Настройки прямого COM через MvIOInterfaceBox (как defect-detector LightService).</summary>
public sealed class IoControllerOptions
{
    public const string SectionName = "IoController";

    /// <summary>Scale255To100 | Percent | Raw255 — см. README в IoControllerComService.</summary>
    public string BrightnessMapping { get; set; } = "Scale255To100";

    /// <summary>
    /// Hold — постоянное свечение (nLightState=1). Trigger — импульс, как MVS/рабочий проект (FlashMode=Trigger).
    /// </summary>
    public string FlashMode { get; set; } = "Trigger";

    /// <summary>Длительность импульса (мс) в режиме Trigger.</summary>
    public int DefaultDurationMs { get; set; } = 180;

    /// <summary>Не закрывать COM после каждого запроса (как рабочий LightService).</summary>
    public bool KeepComSessionOpen { get; set; } = true;
}
