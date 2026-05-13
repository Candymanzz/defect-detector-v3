namespace LightServer.Models;

public class SetLightRequest
{
    /// <summary>
    /// Номер порта: 1-8
    /// </summary>
    public Byte Port { get; set; }
    
    /// <summary>
    /// Яркость: 0-100
    /// </summary>
    public UInt16 Brightness { get; set; }
    
    /// <summary>
    /// Длительность вспышки в мс (0 = постоянно)
    /// </summary>
    public UInt16 DurationMs { get; set; } = 0;
}
