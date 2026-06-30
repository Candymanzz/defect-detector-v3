namespace LightServer;

/// <summary>Порты для SetEnumSerialPorts перед перечислением COM-устройств (из light_hardware.yaml или appsettings).</summary>
public sealed class SerialLightOptions
{
    public const string SectionName = "SerialLight";

    /// <summary>Из light_hardware.yaml (COM-устройства) или appsettings (legacy).</summary>
    public string[] EnumPorts { get; set; } = [];

    /// <summary>Не закрывать MV-LE после каждого POST /api/com/light (On/Off быстрее).</summary>
    public bool KeepDeviceOpen { get; set; } = true;

    /// <summary>Кэш EnumDevices для того же набора портов (сек). 0 — всегда перечислять.</summary>
    public int EnumCacheSeconds { get; set; } = 300;

    /// <summary>Один раз при Open записать яркость на 1–4; On/Off меняют только source (время стабильнее).</summary>
    public bool PreconfigureBrightnessOnOpen { get; set; } = true;

    /// <summary>Hold: один trigger (зажигание) + broadcast sustain. Deferred: импульс. Direct: по каналам.</summary>
    public string FlashSyncMode { get; set; } = "Direct";

    public bool SustainOnAfterTrigger { get; set; } = true;

    /// <summary>Банк POST /api/com/light: On = broadcast On (все каналы разом), Trigger = Timer+software trigger.</summary>
    public string BankFlashMode { get; set; } = "On";

    /// <summary>Только для BankFlashMode=Trigger: после trigger оставить On.</summary>
    public bool BankSustainOnAfterTrigger { get; set; }

    /// <summary>Только для BankFlashMode=Trigger без sustain: длительность импульса (мс).</summary>
    public int BankFlashDurationMs { get; set; } = 500;

    /// <summary>Эксперимент: не брать lock на SDK/COM (возможны гонки и сбои; не для продакшена).</summary>
    public bool DisableSdkLock { get; set; }
}
