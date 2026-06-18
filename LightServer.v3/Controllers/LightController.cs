using LightServer.Models;
using LightServer.Services;
using Microsoft.AspNetCore.Mvc;

namespace LightServer.Controllers;

[ApiController]
[Route("api")]
public sealed class LightController : ControllerBase
{
    private readonly LightControlService _light;

    public LightController(LightControlService light) => _light = light;

    /// <summary>Список устройств (сеть/USB/GenTL), без фильтра COM.</summary>
    [HttpGet("devices")]
    public ActionResult<DeviceListResponse> ListDevices()
    {
        var (ok, error, data) = _light.ListNetworkDevices();
        if (!ok)
            return BadRequest(new { success = false, error });
        return Ok(data);
    }

    /// <summary>Управление MV-LE по индексу из /api/devices.</summary>
    [HttpPost("light")]
    public ActionResult<LightCommandResponse> SetLight([FromBody] LightCommandRequest request)
    {
        var (ok, message, resolvedIndex) = _light.SetLightNetwork(request);
        var response = BuildResponse(ok, message, resolvedIndex ?? request.DeviceIndex, null, request);
        return ok ? Ok(response) : BadRequest(response);
    }

    private static LightCommandResponse BuildResponse(bool ok, string? message, int? deviceIndex, string? comPort, LightCommandRequest r) =>
        new()
        {
            Success = ok,
            Message = ok ? message : null,
            Error = ok ? null : message,
            DeviceIndex = deviceIndex,
            ComPort = comPort,
            LightControllerSource = r.LightControllerSource,
            Channels = r.Channels,
            Brightness = r.Brightness
        };
}
