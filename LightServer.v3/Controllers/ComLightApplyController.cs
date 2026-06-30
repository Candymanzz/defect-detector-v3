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
    private readonly LightHardwareRegistry _hardware;

    public ComLightApplyController(ComLightBankService bank, LightHardwareRegistry hardware)
    {
        _bank = bank;
        _hardware = hardware;
    }

    /// <summary>
    /// Включить или выключить COM-вспышки из light_hardware.yaml.
    /// Неподключённые порты пропускаются — достаточно хотя бы одного COM.
    /// </summary>
    [HttpPost]
    public ActionResult<ComLightStateResponse> SetState([FromBody] ComLightStateRequest request)
    {
        var response = _bank.SetState(request.State, request.Brightness);
        return response.Success ? Ok(response) : BadRequest(response);
    }

    /// <summary>Сконфигурированные и фактически подключённые COM-устройства.</summary>
    [HttpGet]
    public ActionResult<object> Status()
    {
        _bank.EnsureInitialized();

        var devices = _bank.ConfiguredDevices.Select(d => new
        {
            id = d.DeviceId,
            comPort = d.ComPort,
            channels = d.Channels,
            ready = _bank.ReadyDevices.Any(r => string.Equals(r.ComPort, d.ComPort, StringComparison.OrdinalIgnoreCase)),
            skipped = _bank.SkippedPorts.TryGetValue(d.ComPort, out string? reason) ? reason : null
        });

        var readyDevices = _bank.ReadyDevices.Select(d => new
        {
            id = d.DeviceId,
            comPort = d.ComPort,
            channels = d.Channels
        });

        return Ok(new
        {
            initialized = _bank.IsInitialized,
            partial = _bank.IsPartial,
            configPath = _hardware.ConfigPath,
            loadedFromYaml = _hardware.LoadedFromYaml,
            devices,
            readyDevices,
            skippedPorts = _bank.SkippedPorts,
            channelOrder = string.Join(", ",
                _bank.ReadyDevices.SelectMany(static d => d.Channels.Select(ch => $"{d.ComPort}:{ch}"))),
            configuredChannelOrder = string.Join(", ",
                _bank.ConfiguredDevices.SelectMany(static d => d.Channels.Select(ch => $"{d.ComPort}:{ch}"))),
            usage = new
            {
                post = "POST /api/com/light",
                bodyOn = new { state = "on", brightness = "100" },
                bodyOff = new { state = "off" },
                note = "brightness применяется только к подключённым COM (readyDevices)"
            }
        });
    }
}
