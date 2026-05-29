using LightServer.Models;
using LightServer.Services;
using Microsoft.AspNetCore.Mvc;

namespace LightServer.Controllers;

/// <summary>COM-подсветка: один POST — on/off и яркость % через запятую.</summary>
[ApiController]
[Route("api/com/light")]
public sealed class ComLightApplyController : ControllerBase
{
    private readonly ComLightBankService _bank;

    public ComLightApplyController(ComLightBankService bank) => _bank = bank;

    /// <summary>
    /// Включить или выключить все вспышки (COM1–COM3 из appsettings).
    /// Тело: { "state": "on", "brightness": "100" } или { "state": "off" }.
    /// </summary>
    [HttpPost]
    public ActionResult<ComLightStateResponse> SetState([FromBody] ComLightStateRequest request)
    {
        var response = _bank.SetState(request.State, request.Brightness);
        return response.Success ? Ok(response) : BadRequest(response);
    }

    /// <summary>Сконфигурированные COM (COM1: 2ch, COM2/COM3: 4ch).</summary>
    [HttpGet]
    public ActionResult<object> Status()
    {
        var devices = _bank.ConfiguredDevices.Select(static d => new
        {
            d.ComPort,
            d.Channels
        });
        return Ok(new
        {
            initialized = _bank.IsInitialized,
            devices,
            usage = new
            {
                post = "POST /api/com/light",
                bodyOn = new { state = "on", brightness = "100" },
                bodyOff = new { state = "off" }
            }
        });
    }
}
