namespace LightServer.Services;

/// <summary>Возможности MV-LE, определённые один раз при Open (не на каждый POST).</summary>
public readonly record struct MvLeDeviceCapabilities(bool HasLightController, bool HasBrightness);
