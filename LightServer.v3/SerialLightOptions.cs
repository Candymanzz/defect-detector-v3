namespace LightServer;

/// <summary>Порты для SetEnumSerialPorts перед перечислением COM-устройств (appsettings: SerialLight:EnumPorts).</summary>
public sealed class SerialLightOptions
{
    public const string SectionName = "SerialLight";

    /// <summary>Например COM1, COM3. Используется по умолчанию для GET /api/com/devices.</summary>
    public string[] EnumPorts { get; set; } = ["COM1", "COM3"];

    /// <summary>Не закрывать MV-LE после каждого POST /api/com/light (On/Off быстрее).</summary>
    public bool KeepDeviceOpen { get; set; } = true;

    /// <summary>Кэш EnumDevices для того же набора портов (сек). 0 — всегда перечислять.</summary>
    public int EnumCacheSeconds { get; set; } = 300;

    /// <summary>Один раз при Open записать яркость на 1–4; On/Off меняют только source (время стабильнее).</summary>
    public bool PreconfigureBrightnessOnOpen { get; set; } = true;

    /// <summary>Hold: один trigger (зажигание) + broadcast sustain. Deferred: импульс. Direct: по каналам.</summary>
    public string FlashSyncMode { get; set; } = "Hold";

    /// <summary>После software trigger перевести каналы в On (удержание до POST Off), иначе импульс Timer1 ~500 ms.</summary>
    public bool SustainOnAfterTrigger { get; set; } = true;

    /// <summary>Эксперимент: не брать lock на SDK/COM (возможны гонки и сбои; не для продакшена).</summary>
    public bool DisableSdkLock { get; set; }
}
