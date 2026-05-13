namespace LightServer.Models;

public class FlashRequest
{
    /// <summary>
    /// Номер порта: 1-8
    /// </summary>
    public byte Port { get; set; }
    
    /// <summary>
    /// Яркость: 0-100
    /// </summary>
    public UInt16 Brightness { get; set; } = 100;
    
    /// <summary>
    /// Длительность вспышки в мс
    /// </summary>
    public UInt16 DurationMs { get; set; } = 100;
}
