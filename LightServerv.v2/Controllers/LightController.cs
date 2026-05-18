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

    /// <summary>Список устройств MVS (найти индекс MV-LE).</summary>
    [HttpGet("devices")]
    public ActionResult<DeviceListResponse> ListDevices()
    {
        var (ok, error, data) = _light.ListDevices();
        if (!ok)
            return BadRequest(new LightCommandResponse { Success = false, Error = error });
        return Ok(data);
    }

    /// <summary>Вкл/выкл и яркость (LightBrightness 0–255) на каналах MV-LE.</summary>
    [HttpPost("light")]
    public ActionResult<LightCommandResponse> SetLight([FromBody] LightCommandRequest request)
    {
        var (ok, message) = _light.SetLight(request);
        var response = new LightCommandResponse
        {
            DeviceIndex = request.DeviceIndex,
            LightControllerSource = request.LightControllerSource,
            Channels = request.Channels,
            Brightness = request.Brightness
        };

        if (!ok)
        {
            response.Success = false;
            response.Error = message;
            return BadRequest(response);
        }

        response.Success = true;
        response.Message = message;
        return Ok(response);
    }
}
