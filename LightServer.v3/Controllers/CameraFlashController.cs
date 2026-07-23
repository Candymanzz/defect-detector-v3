using LightServer.Models;
using LightServer.Services;
using Microsoft.AspNetCore.Mvc;

namespace LightServer.Controllers;

[ApiController]
[Route("api/camera-flash")]
public sealed class CameraFlashController : ControllerBase
{
    private readonly LightControlService _light;
    private readonly LightHardwareRegistry _hardware;
    private readonly EthernetMvLeBank _ethernetBank;

    public CameraFlashController(
        LightControlService light,
        LightHardwareRegistry hardware,
        EthernetMvLeBank ethernetBank)
    {
        _light = light;
        _hardware = hardware;
        _ethernetBank = ethernetBank;
    }

    /// <summary>
    /// Камеры с двумя каналами в camera_routes (light_hardware.yaml): левая/правая вспышка.
    /// </summary>
    [HttpPost("pair")]
    public ActionResult<object> SetPair([FromBody] CameraPairFlashRequest request)
    {
        _hardware.EnsureFresh();

        if (!IsPowerInRange(request.LeftPower) || !IsPowerInRange(request.RightPower))
            return BadRequest(new { success = false, error = "leftPower/rightPower должны быть в диапазоне 0..255." });

        if (!TryResolveRoute(request.CameraNumber, minChannels: 2, maxChannels: null, out RouteTarget? target, out string? routeError))
            return BadRequest(new { success = false, error = routeError });

        int[] channels = [target!.Channels[0], target.Channels[1]];
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

            if (ok)
                CameraFlashBrightnessCache.RememberCom(target.ComPort, channels, brightness);

            return ok
                ? Ok(new
                {
                    success = true,
                    cameraNumber = request.CameraNumber,
                    deviceId = target.DeviceId,
                    route = "com",
                    comPort = target.ComPort,
                    channels,
                    brightness
                })
                : BadRequest(new
                {
                    success = false,
                    cameraNumber = request.CameraNumber,
                    deviceId = target.DeviceId,
                    route = "com",
                    comPort = target.ComPort,
                    channels,
                    brightness,
                    error
                });
        }

        int[] allChannels = target.DeviceChannels;
        int[] mergedBrightness = CameraFlashBrightnessCache.MergeNetworkPair(
            target.IpAddress!,
            allChannels,
            target.Channels[0],
            target.Channels[1],
            request.LeftPower,
            request.RightPower);

        // Только яркость в кэш/регистры (без On): 8× Off→On на /pair давали 5–6 кадров задержки.
        // Применение в цикле: live WriteBrightness если банк On; иначе primed → следующий bank On (DI/idle).
        // Во время DI3→кадр оркестратор откладывает /pair до после Off.
        if (_ethernetBank.TryGet(target.IpAddress!, out _))
        {
            var (bankOk, bankErr) = _ethernetBank.ApplyBrightnessIp(target.IpAddress!, allChannels, mergedBrightness);
            if (bankOk)
            {
                CameraFlashBrightnessCache.RememberNetwork(
                    target.IpAddress!,
                    allChannels,
                    target.Channels[0],
                    target.Channels[1],
                    request.LeftPower,
                    request.RightPower);
            }

            return bankOk
                ? Ok(new
                {
                    success = true,
                    cameraNumber = request.CameraNumber,
                    deviceId = target.DeviceId,
                    route = "ethernet-bank-brightness",
                    ipAddress = target.IpAddress,
                    channels,
                    brightness
                })
                : BadRequest(new
                {
                    success = false,
                    cameraNumber = request.CameraNumber,
                    deviceId = target.DeviceId,
                    route = "ethernet-bank-brightness",
                    ipAddress = target.IpAddress,
                    channels,
                    brightness,
                    error = bankErr
                });
        }

        var (netOk, netError, resolvedIndex) = _light.SetLightNetwork(
            new LightCommandRequest
            {
                IpAddress = target.IpAddress,
                LightControllerSource = "On",
                Channels = allChannels,
                Brightness = mergedBrightness
            });

        if (netOk)
            CameraFlashBrightnessCache.RememberNetwork(
                target.IpAddress!,
                allChannels,
                target.Channels[0],
                target.Channels[1],
                request.LeftPower,
                request.RightPower);

        return netOk
            ? Ok(new
            {
                success = true,
                cameraNumber = request.CameraNumber,
                deviceId = target.DeviceId,
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
                deviceId = target.DeviceId,
                route = "network",
                ipAddress = target.IpAddress,
                channels,
                brightness,
                error = netError
            });
    }

    /// <summary>
    /// Одна вспышка: camera_routes с ровно одним каналом (channels: [1] или channels: 1).
    /// </summary>
    [HttpPost("single")]
    public ActionResult<object> SetSingle([FromBody] CameraSingleFlashRequest request)
    {
        _hardware.EnsureFresh();

        if (!IsPowerInRange(request.Power))
            return BadRequest(new { success = false, error = "power должен быть в диапазоне 0..255." });

        if (!TryResolveRoute(request.CameraNumber, minChannels: 1, maxChannels: 1, out RouteTarget? target, out string? routeError))
            return BadRequest(new { success = false, error = routeError });

        int[] channels = [target.Channels[0]];
        int[] brightness = [request.Power];

        if (target.ComPort == null)
            return BadRequest(new { success = false, error = $"cameraNumber {request.CameraNumber}: single поддерживает только COM-устройства." });

        var (ok, error) = _light.ApplyComPort(
            new LightCommandRequestCom
            {
                ComPort = target.ComPort,
                LightControllerSource = "On",
                Channels = channels,
                Brightness = brightness
            },
            defaultChannels: channels);

        if (ok)
            CameraFlashBrightnessCache.RememberCom(target.ComPort, channels, brightness);

        return ok
            ? Ok(new
            {
                success = true,
                cameraNumber = request.CameraNumber,
                deviceId = target.DeviceId,
                route = "com",
                comPort = target.ComPort,
                channels,
                brightness
            })
            : BadRequest(new
            {
                success = false,
                cameraNumber = request.CameraNumber,
                deviceId = target.DeviceId,
                route = "com",
                comPort = target.ComPort,
                channels,
                brightness,
                error
            });
    }

    /// <summary>Загруженные camera_routes (для отладки, без перезапуска сервера).</summary>
    [HttpGet("routes")]
    public ActionResult<object> ListRoutes()
    {
        _hardware.EnsureFresh();
        var routes = _hardware.CameraRoutes.Select(r => new
        {
            cameraNumber = r.CameraNumber,
            deviceId = r.DeviceId,
            channels = r.Channels,
            mode = r.Channels.Length == 1 ? "single" : r.Channels.Length >= 2 ? "pair" : "unknown"
        });
        return Ok(new
        {
            configPath = _hardware.ConfigPath,
            loadedFromYaml = _hardware.LoadedFromYaml,
            routes
        });
    }

    private bool TryResolveRoute(int cameraNumber, int minChannels, int? maxChannels, out RouteTarget? target, out string? error)
    {
        target = null;
        error = null;

        if (!_hardware.TryGetRoute(cameraNumber, out LightCameraRouteEntry route))
        {
            error = $"cameraNumber {cameraNumber} не найден в camera_routes (config/blocks/51-light-hardware.yaml).";
            return false;
        }

        if (route.Channels.Length < minChannels)
        {
            error = $"cameraNumber {cameraNumber}: нужно минимум {minChannels} канал(а) в camera_routes, задано {route.Channels.Length}.";
            return false;
        }

        if (maxChannels is int max && route.Channels.Length > max)
        {
            error = max == 1
                ? $"cameraNumber {cameraNumber}: для single нужен 1 канал в camera_routes (сейчас {route.Channels.Length}: [{string.Join(", ", route.Channels)}]). Используйте POST /api/camera-flash/pair или задайте channels: [N]."
                : $"cameraNumber {cameraNumber}: слишком много каналов ({route.Channels.Length}), максимум {max}.";
            return false;
        }

        if (!_hardware.TryGetDevice(route.DeviceId, out LightHardwareDeviceEntry device))
        {
            error = $"cameraNumber {cameraNumber}: device_id '{route.DeviceId}' не найден в devices.";
            return false;
        }

        if (!device.Enabled)
        {
            error = $"cameraNumber {cameraNumber}: устройство '{route.DeviceId}' отключено (enabled: false).";
            return false;
        }

        if (device.IsCom)
        {
            if (device.ComPort.Length == 0)
            {
                error = $"устройство '{route.DeviceId}': не указан com_port.";
                return false;
            }

            target = new RouteTarget(route.DeviceId, device.ComPort, null, route.Channels, device.Channels);
            return true;
        }

        if (device.IsEthernet)
        {
            if (string.IsNullOrWhiteSpace(device.Ip))
            {
                error = $"устройство '{route.DeviceId}': не указан ip.";
                return false;
            }

            target = new RouteTarget(route.DeviceId, null, device.Ip, route.Channels, device.Channels);
            return true;
        }

        error = $"устройство '{route.DeviceId}': неизвестный type '{device.Type}' (ожидается com или ethernet).";
        return false;
    }

    private static bool IsPowerInRange(int value) => value is >= 0 and <= 255;

    private sealed record RouteTarget(
        string DeviceId,
        string? ComPort,
        string? IpAddress,
        int[] Channels,
        int[] DeviceChannels);
}
