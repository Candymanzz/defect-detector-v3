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
    private readonly IoControllerComService _ioCom;
    public ComLightController(LightControlService light, IoControllerComService ioCom)
    {
        _light = light;
        _ioCom = ioCom;
    }

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

    /// <summary>Управление подсветкой по COM: укажите comPort.</summary>
    [HttpPost("light")]
    public ActionResult<LightCommandResponse> SetLightSerial([FromBody] LightCommandRequestCom request, [FromQuery] string? ports)
    {
        var portList = ParsePortsQuery(ports);
        // MV-LE — MvCameraControl; IoCom только если MvCamera не смог (иначе IoCom держит COM2 и ломает следующий On).
        var (ok, message) = _light.SetLightSerial(request, portList);
        if (!ok)
        {
            var (ioOk, ioMsg) = _ioCom.SetLight(request);
            if (ioOk)
            {
                ok = true;
                message = $"MvCameraControl: {message} | {ioMsg}";
            }
            else
            {
                message = ioMsg ?? message;
            }
        }

        var response = new LightCommandResponse
        {
            Success = ok,
            Message = ok ? message : null,
            Error = ok ? null : message,
            ComPort = request.ComPort,
            LightControllerSource = request.LightControllerSource,
            Channels = request.Channels,
            Brightness = request.Brightness
        };
        return ok ? Ok(response) : BadRequest(response);
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
