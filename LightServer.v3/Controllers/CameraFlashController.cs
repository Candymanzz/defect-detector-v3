using LightServer.Models;
using LightServer.Services;
using Microsoft.AspNetCore.Mvc;
using System.Collections.Concurrent;

namespace LightServer.Controllers;

[ApiController]
[Route("api/camera-flash")]
public sealed class CameraFlashController : ControllerBase
{
    private readonly LightControlService _light;
    private static readonly int[] AllNetworkChannels = [1, 2, 3, 4];
    private static readonly ConcurrentDictionary<string, int[]> NetworkBrightnessState = new(StringComparer.OrdinalIgnoreCase);
    private static readonly object NetworkStateLock = new();

    public CameraFlashController(LightControlService light) => _light = light;

    /// <summary>
    /// Камеры 1..8: установить мощность левой/правой вспышки.
    /// 1,2 -> COM3 (1/2, 3/4), 3,4 -> COM2 (1/2, 3/4),
    /// 5,6 -> 169.254.213.1 (1/2, 3/4), 7,8 -> 169.254.213.2 (1/2, 3/4).
    /// </summary>
    [HttpPost("pair")]
    public ActionResult<object> SetPair([FromBody] CameraPairFlashRequest request)
    {
        if (request.CameraNumber is < 1 or > 8)
            return BadRequest(new { success = false, error = "cameraNumber должен быть в диапазоне 1..8." });
        if (!IsPowerInRange(request.LeftPower) || !IsPowerInRange(request.RightPower))
            return BadRequest(new { success = false, error = "leftPower/rightPower должны быть в диапазоне 0..255." });

        var target = ResolvePairTarget(request.CameraNumber);
        int[] channels = [target.LeftChannel, target.RightChannel];
        int[] brightness = [request.LeftPower, request.RightPower];

        if (target.ComPort != null)
        {
            var (ok, error) = _light.ApplyComPort(
                new LightCommandRequestCom
                {
                    ComPort = target.ComPort,
                    LightControllerSource = "On",
                    Channels = channels,
                    Brightness = brightness
                },
                defaultChannels: channels);

            return ok
                ? Ok(new
                {
                    success = true,
                    cameraNumber = request.CameraNumber,
                    route = "com",
                    comPort = target.ComPort,
                    channels,
                    brightness
                })
                : BadRequest(new
                {
                    success = false,
                    cameraNumber = request.CameraNumber,
                    route = "com",
                    comPort = target.ComPort,
                    channels,
                    brightness,
                    error
                });
        }

        var (netOk, netError, resolvedIndex) = _light.SetLightNetwork(
            new LightCommandRequest
            {
                IpAddress = target.IpAddress,
                LightControllerSource = "On",
                Channels = AllNetworkChannels,
                Brightness = BuildMergedNetworkBrightness(target.IpAddress!, target.LeftChannel, target.RightChannel, request.LeftPower, request.RightPower)
            });

        if (netOk)
            RememberNetworkBrightness(target.IpAddress!, target.LeftChannel, target.RightChannel, request.LeftPower, request.RightPower);

        return netOk
            ? Ok(new
            {
                success = true,
                cameraNumber = request.CameraNumber,
                route = "network",
                ipAddress = target.IpAddress,
                deviceIndex = resolvedIndex,
                channels,
                brightness
            })
            : BadRequest(new
            {
                success = false,
                cameraNumber = request.CameraNumber,
                route = "network",
                ipAddress = target.IpAddress,
                channels,
                brightness,
                error = netError
            });
    }

    /// <summary>
    /// Камеры 9..10: установить мощность одной вспышки.
    /// 9 -> COM1 ch1, 10 -> COM1 ch2.
    /// </summary>
    [HttpPost("single")]
    public ActionResult<object> SetSingle([FromBody] CameraSingleFlashRequest request)
    {
        if (request.CameraNumber is < 9 or > 10)
            return BadRequest(new { success = false, error = "cameraNumber должен быть 9 или 10." });
        if (!IsPowerInRange(request.Power))
            return BadRequest(new { success = false, error = "power должен быть в диапазоне 0..255." });

        int channel = request.CameraNumber == 9 ? 1 : 2;
        int[] channels = [channel];
        int[] brightness = [request.Power];

        var (ok, error) = _light.ApplyComPort(
            new LightCommandRequestCom
            {
                ComPort = "COM1",
                LightControllerSource = "On",
                Channels = channels,
                Brightness = brightness
            },
            defaultChannels: channels);

        return ok
            ? Ok(new
            {
                success = true,
                cameraNumber = request.CameraNumber,
                route = "com",
                comPort = "COM1",
                channels,
                brightness
            })
            : BadRequest(new
            {
                success = false,
                cameraNumber = request.CameraNumber,
                route = "com",
                comPort = "COM1",
                channels,
                brightness,
                error
            });
    }

    private static bool IsPowerInRange(int value) => value is >= 0 and <= 255;

    private static PairTarget ResolvePairTarget(int cameraNumber)
    {
        return cameraNumber switch
        {
            1 => new PairTarget("COM3", null, 1, 2),
            2 => new PairTarget("COM3", null, 3, 4),
            3 => new PairTarget("COM2", null, 1, 2),
            4 => new PairTarget("COM2", null, 3, 4),
            5 => new PairTarget(null, "169.254.213.1", 1, 2),
            6 => new PairTarget(null, "169.254.213.1", 3, 4),
            7 => new PairTarget(null, "169.254.213.2", 1, 2),
            8 => new PairTarget(null, "169.254.213.2", 3, 4),
            _ => throw new ArgumentOutOfRangeException(nameof(cameraNumber), cameraNumber, "cameraNumber вне диапазона 1..8")
        };
    }

    private static int[] BuildMergedNetworkBrightness(
        string ipAddress,
        int leftChannel,
        int rightChannel,
        int leftPower,
        int rightPower)
    {
        lock (NetworkStateLock)
        {
            int[] merged = NetworkBrightnessState.TryGetValue(ipAddress, out int[]? existing) && existing.Length == 4
                ? (int[])existing.Clone()
                : [0, 0, 0, 0];

            merged[leftChannel - 1] = leftPower;
            merged[rightChannel - 1] = rightPower;
            return merged;
        }
    }

    private static void RememberNetworkBrightness(
        string ipAddress,
        int leftChannel,
        int rightChannel,
        int leftPower,
        int rightPower)
    {
        lock (NetworkStateLock)
        {
            int[] merged = NetworkBrightnessState.TryGetValue(ipAddress, out int[]? existing) && existing.Length == 4
                ? (int[])existing.Clone()
                : [0, 0, 0, 0];

            merged[leftChannel - 1] = leftPower;
            merged[rightChannel - 1] = rightPower;
            NetworkBrightnessState[ipAddress] = merged;
        }
    }

    private sealed record PairTarget(string? ComPort, string? IpAddress, int LeftChannel, int RightChannel);
}
