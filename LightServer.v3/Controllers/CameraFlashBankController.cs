using LightServer.Models;
using LightServer.Services;
using Microsoft.AspNetCore.Mvc;

namespace LightServer.Controllers;

/// <summary>
/// Банк вспышек: Ethernet GigE MV-LE + COM из light_hardware.yaml.
/// Capture pipeline оркестратора этот endpoint не вызывает (только interval_flash / hold).
/// </summary>
[ApiController]
[Route("api/camera-flash")]
public sealed class CameraFlashBankController : ControllerBase
{
    private readonly EthernetMvLeBank _ethernetBank;
    private readonly ComLightBankService _comBank;
    private readonly LightHardwareRegistry _hardware;
    private readonly ILogger<CameraFlashBankController> _log;

    public CameraFlashBankController(
        EthernetMvLeBank ethernetBank,
        ComLightBankService comBank,
        LightHardwareRegistry hardware,
        ILogger<CameraFlashBankController> log)
    {
        _ethernetBank = ethernetBank;
        _comBank = comBank;
        _hardware = hardware;
        _log = log;
    }

    /// <summary>
    /// Включить или выключить все Ethernet MV-LE и COM разом.
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

        var (ethReady, initErr) = _ethernetBank.EnsureInitialized();
        if (!ethReady)
        {
            return BadRequest(new
            {
                success = false,
                error = initErr ?? "Ethernet bank не готов (проверьте light_hardware ethernet devices)."
            });
        }

        IReadOnlyDictionary<string, int[]>? brightnessByIp = null;
        if (turnOn && request.BrightnessByIp is { Count: > 0 })
            brightnessByIp = request.BrightnessByIp;

        IReadOnlyList<(string Ip, bool Ok, string Message)> ethResults = turnOn
            ? _ethernetBank.ApplyAllOn(brightnessByIp)
            : _ethernetBank.ApplyAllOff();

        string[] ethApplied = ethResults.Where(static r => r.Ok).Select(static r => $"{r.Ip}: {r.Message}").ToArray();
        string[] ethErrors = ethResults.Where(static r => !r.Ok).Select(static r => $"{r.Ip}: {r.Message}").ToArray();

        _hardware.EnsureFresh();
        string comBrightnessCsv = turnOn
            ? CameraFlashBrightnessCache.BuildComBrightnessCsv(_hardware)
            : null!;
        ComLightStateResponse comResponse = _comBank.SetState(turnOn ? "on" : "off", turnOn ? comBrightnessCsv : null);

        string onOffLabel = turnOn ? "on" : "off";
        string[] comApplied = (comResponse.Results ?? Array.Empty<ComLightApplyResultItem>())
            .Where(static r => r.Success)
            .Select(r => $"{r.ComPort}: {r.Message ?? onOffLabel}")
            .ToArray();
        string[] comErrors = (comResponse.Results ?? Array.Empty<ComLightApplyResultItem>())
            .Where(static r => !r.Success && !r.Skipped)
            .Select(static r => $"{r.ComPort}: {r.Error ?? r.Message ?? "fail"}")
            .ToArray();
        if (!comResponse.Success && !string.IsNullOrWhiteSpace(comResponse.Error) && comErrors.Length == 0)
            comErrors = [comResponse.Error];

        string[] applied = ethApplied.Concat(comApplied).ToArray();
        string[] errors = ethErrors.Concat(comErrors).ToArray();
        // COM может быть не подключён — пустой com bank = success skip; ethernet обязателен.
        bool comOk = comResponse.Success || comApplied.Length + comErrors.Length == 0;
        bool success = ethErrors.Length == 0 && ethApplied.Length > 0 && comOk;

        _log.LogInformation(
            "camera-flash/bank {State}: eth ready={EthReady} ok={EthOk} err={EthErr}; com ok={ComOk} applied={ComApplied} err={ComErr} brightnessCsv={Csv}",
            turnOn ? "on" : "off",
            _ethernetBank.ReadyCount,
            ethApplied.Length,
            ethErrors.Length,
            comOk,
            comApplied.Length,
            comErrors.Length,
            turnOn ? comBrightnessCsv : "-");

        var body = new
        {
            success,
            state = turnOn ? "on" : "off",
            transport = "ethernet-gige+com",
            ready = _ethernetBank.ReadyCount,
            applied,
            errors,
            skipped = _ethernetBank.Skipped,
            com = new
            {
                success = comResponse.Success,
                message = comResponse.Message,
                error = comResponse.Error,
                applied = comApplied,
                errors = comErrors
            },
            error = success ? null : string.Join("; ", errors)
        };
        return success ? Ok(body) : BadRequest(body);
    }

    /// <summary>Статус Ethernet+COM банка для оркестратора (awaitEndpointsReady).</summary>
    [HttpGet("bank")]
    public ActionResult<object> BankStatus()
    {
        var (ethReady, initErr) = _ethernetBank.EnsureInitialized();
        var (comReady, comErr) = _comBank.EnsureInitialized();
        return Ok(new
        {
            initialized = ethReady,
            ready = _ethernetBank.ReadyCount,
            transport = "ethernet-gige+com",
            skipped = _ethernetBank.Skipped,
            com = new
            {
                initialized = comReady,
                ready = _comBank.ReadyDevices.Count,
                error = comReady ? null : comErr
            },
            error = ethReady ? null : initErr
        });
    }
}
