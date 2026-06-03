using LightServer.Models;
using LightServer.Services;
using Microsoft.AspNetCore.Mvc;
namespace LightServer.Controllers;

/// <summary>Те же операции, что /api/devices и /api/light, но для устройств на COM: перед перечислением вызывается SetEnumSerialPorts.</summary>
[ApiController]
[Route("api/com")]
public sealed class ComLightController : ControllerBase
{
    private readonly LightControlService _light;

    public ComLightController(LightControlService light) => _light = light;

    /// <summary>Список устройств на указанных COM-портах. Порты: ?ports=COM1,COM3 или из appsettings SerialLight:EnumPorts.</summary>
    [HttpGet("devices")]
    public ActionResult<DeviceListResponse> ListSerialDevices([FromQuery] string? ports)
    {
        var portList = ParsePortsQuery(ports);
        var (ok, error, data) = _light.ListSerialDevices(portList);
        if (!ok)
            return BadRequest(new { success = false, error });

        var merged = new List<DeviceInfoDto>(data!.Devices);
        var existingPorts = new HashSet<string>(
            merged.Where(static d => !string.IsNullOrWhiteSpace(d.ComPort)).Select(static d => d.ComPort!),
            StringComparer.OrdinalIgnoreCase);
        var requestedPorts = portList is { Count: > 0 }
            ? new HashSet<string>(portList, StringComparer.OrdinalIgnoreCase)
            : null;

        foreach (string p in IoControllerComService.GetHostComPorts())
        {
            if (requestedPorts != null && !requestedPorts.Contains(p))
                continue;
            if (existingPorts.Contains(p))
                continue;

            merged.Add(new DeviceInfoDto
            {
                Index = -1,
                TLayerType = "HostComPort",
                ModelName = "Host COM Port",
                SerialNumber = "",
                ComPort = p
            });
        }

        data = new DeviceListResponse { Count = merged.Count, Devices = merged };
        return Ok(data);
    }

    private static List<string>? ParsePortsQuery(string? ports)
    {
        if (string.IsNullOrWhiteSpace(ports))
            return null;

        return ports.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(static s => s.Length > 0)
            .ToList();
    }
}
