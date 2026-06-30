using System.IO.Ports;

namespace IoInputMonitor;

internal static class IoBoxProbe
{
    public sealed record ProbeResult(string ComPort, bool Opened, bool HasIoBoard, string? Firmware, string? Error);

    public static IReadOnlyList<ProbeResult> ScanAllPorts()
    {
        string[] ports = SerialPort.GetPortNames()
            .OrderBy(static p => p, StringComparer.OrdinalIgnoreCase)
            .ToArray();

        var results = new List<ProbeResult>(ports.Length);
        foreach (string port in ports)
            results.Add(ProbePort(port));

        return results;
    }

    public static ProbeResult ProbePort(string comPort)
    {
        try
        {
            using var session = new IoBoxSession(comPort);
            session.Open();

            if (!session.TryReadFirmwareVersion(out MvIoNative.MvIoVersion firmware))
            {
                return new ProbeResult(
                    comPort,
                    Opened: true,
                    HasIoBoard: false,
                    Firmware: null,
                    Error: "COM открылся, но это не IO box (скорее MV-LE подсветка). DI здесь недоступны.");
            }

            string fw = $"{firmware.Main}.{firmware.Sub}.{firmware.Modify} ({firmware.Year:D4}-{firmware.Month:D2}-{firmware.Day:D2})";
            return new ProbeResult(comPort, true, true, fw, null);
        }
        catch (Exception ex)
        {
            return new ProbeResult(comPort, false, false, null, ex.Message);
        }
    }

    public static string? FindIoBoardComPort()
    {
        foreach (ProbeResult result in ScanAllPorts())
        {
            if (result.HasIoBoard)
                return result.ComPort;
        }

        return null;
    }
}
