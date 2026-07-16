using LightServer.Models;
using LightServer.Services;
using Microsoft.AspNetCore.Mvc;

namespace LightServer.Controllers;

/// <summary>
/// Банк вспышек по Ethernet (GigE MV-LE): параллельный On/Off с Barrier.
/// COM не используется. Capture pipeline оркестратора этот endpoint не вызывает.
/// </summary>
[ApiController]
[Route("api/camera-flash")]
public sealed class CameraFlashBankController : ControllerBase
{
    private readonly EthernetMvLeBank _ethernetBank;
    private readonly ILogger<CameraFlashBankController> _log;

    public CameraFlashBankController(EthernetMvLeBank ethernetBank, ILogger<CameraFlashBankController> log)
    {
        _ethernetBank = ethernetBank;
        _log = log;
    }

    /// <summary>
    /// Включить или выключить все Ethernet MV-LE разом (постоянные GigE-сессии + barrier).
    /// </summary>
    [HttpPost("bank")]
    public ActionResult<object> SetBank([FromBody] CameraFlashBankRequest request)
    {
        string state = (request.State ?? "off").Trim();
        bool turnOn = state.Equals("on", StringComparison.OrdinalIgnoreCase)
                      || state.Equals("1", StringComparison.OrdinalIgnoreCase);
        bool turnOff = state.Equals("off", StringComparison.OrdinalIgnoreCase)
                       || state.Equals("0", StringComparison.OrdinalIgnoreCase);
        if (!turnOn && !turnOff)
            return BadRequest(new { success = false, error = "state должен быть on или off." });

        var (ready, initErr) = _ethernetBank.EnsureInitialized();
        if (!ready)
        {
            return BadRequest(new
            {
                success = false,
                error = initErr ?? "Ethernet bank не готов (проверьте light_hardware ethernet devices)."
            });
        }

        IReadOnlyList<(string Ip, bool Ok, string Message)> results = turnOn
            ? _ethernetBank.ApplyAllOn(brightnessByIp: null)
            : _ethernetBank.ApplyAllOff();

        string[] applied = results.Where(static r => r.Ok).Select(static r => $"{r.Ip}: {r.Message}").ToArray();
        string[] errors = results.Where(static r => !r.Ok).Select(static r => $"{r.Ip}: {r.Message}").ToArray();
        bool success = errors.Length == 0 && applied.Length > 0;

        _log.LogInformation(
            "camera-flash/bank {State}: ready={Ready} ok={Ok} err={Err}",
            turnOn ? "on" : "off",
            _ethernetBank.ReadyCount,
            applied.Length,
            errors.Length);

        var body = new
        {
            success,
            state = turnOn ? "on" : "off",
            transport = "ethernet-gige",
            ready = _ethernetBank.ReadyCount,
            applied,
            errors,
            skipped = _ethernetBank.Skipped,
            error = success ? null : string.Join("; ", errors)
        };
        return success ? Ok(body) : BadRequest(body);
    }
}
