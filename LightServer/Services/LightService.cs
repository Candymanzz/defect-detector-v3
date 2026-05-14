using System.Linq;
using Microsoft.Extensions.Configuration;
using NsIOControllerSDK;

namespace LightServer.Services;

public class LightService : IDisposable
{
    private readonly ILogger<LightService> _logger;
    private IntPtr _handle;
    private bool _isInitialized;
    private readonly bool _simulateHardware;
    private readonly bool _simulateIfInitFailed;
    private bool _simulateMode;
    private readonly string _comPort;
    private readonly int _defaultBrightness;
    private readonly int _defaultDurationMs;
    private readonly Dictionary<int, int> _cameraPortMapping;
    private readonly bool _turnOffAllOnInit;
    /** true = режим как в рабочей сборке: включение → задержка → выключение (не MV_IO_LIGHTSTATE_TRIGGER). */
    private readonly bool _flashHoldMode;
    /** Если не пусто — при каждом trigger-inspection включаются все эти порты сразу (кольцевая подсветка). Иначе — один порт из CameraPortMap по camera_id. */
    private readonly int[] _inspectionFlashPorts;
    private readonly SemaphoreSlim _lock = new SemaphoreSlim(1, 1);
    private bool _disposed;

    /// <summary>Значения nPortNumber для данного контроллера (как в рабочей сборке; не менять на «канонические» биты SDK без проверки на железе).</summary>
    private readonly Dictionary<int, byte> _portMapping = new()
    {
        { 1, 0x00 },
        { 2, 0x01 },
        { 3, 0x02 },
        { 4, 0x03 },
    };

    public LightService(ILogger<LightService> logger, IConfiguration configuration)
    {
        _logger = logger;
        _comPort = configuration["LightSettings:ComPort"] ?? "COM3";
        _defaultBrightness = Math.Clamp(configuration.GetValue("LightSettings:DefaultBrightness", 100), 0, 100);
        _defaultDurationMs = Math.Clamp(configuration.GetValue("LightSettings:DefaultDurationMs", 100), 1, 5000);
        _cameraPortMapping = LoadCameraPortMap(configuration);
        _simulateHardware = configuration.GetValue("LightSettings:SimulateHardware", false);
        _simulateIfInitFailed = configuration.GetValue("LightSettings:SimulateIfInitFailed", true);
        _turnOffAllOnInit = configuration.GetValue("LightSettings:TurnOffAllOnInit", false);
        string flashMode = configuration["LightSettings:FlashMode"] ?? "Hold";
        _flashHoldMode = !string.Equals(flashMode, "Trigger", StringComparison.OrdinalIgnoreCase);
        _inspectionFlashPorts = ValidateInspectionFlashPorts(logger, LoadInspectionFlashPortsRaw(configuration));
        _handle = IntPtr.Zero;
        _isInitialized = false;
    }

    /// <summary>Вспышка идёт без COM (задержка по duration), железо не используется.</summary>
    public bool IsSimulated => _simulateMode;

    private static Dictionary<int, int> LoadCameraPortMap(IConfiguration configuration)
    {
        var section = configuration.GetSection("LightSettings:CameraPortMap");
        var bound = section.Get<Dictionary<int, int>>();
        if (bound is { Count: > 0 })
            return bound;

        var map = new Dictionary<int, int>();
        foreach (var child in section.GetChildren())
        {
            if (!int.TryParse(child.Key, out var camId))
                continue;
            int port = configuration.GetValue<int?>($"LightSettings:CameraPortMap:{camId}") ?? 0;
            if (port <= 0 && int.TryParse(child.Value, out var parsed))
                port = parsed;
            if (port > 0)
                map[camId] = port;
        }
        return map;
    }

    private static int[] LoadInspectionFlashPortsRaw(IConfiguration configuration)
    {
        int[]? arr = configuration.GetSection("LightSettings:InspectionFlashPorts").Get<int[]>();
        if (arr == null || arr.Length == 0)
            return Array.Empty<int>();
        return arr.Distinct().OrderBy(p => p).ToArray();
    }

    private static int[] ValidateInspectionFlashPorts(ILogger<LightService> logger, int[] raw)
    {
        if (raw.Length == 0)
            return Array.Empty<int>();
        var ok = new List<int>();
        foreach (int p in raw)
        {
            if (p is >= 1 and <= 8)
                ok.Add(p);
            else
                logger.LogWarning("LightSettings:InspectionFlashPorts — порт {Port} вне диапазона 1–8, пропуск", p);
        }
        return ok.ToArray();
    }

    public async Task<bool> InitializeAsync()
    {
        return await Initialize();
    }

    private async Task<bool> Initialize()
    {
        bool turnOffAfterRelease = false;
        bool success = false;

        await _lock.WaitAsync();
        try
        {
            if (_isInitialized || _simulateMode)
            {
                _logger.LogInformation("LightService уже готов (hardware={Hw}, simulated={Sim})", _isInitialized, _simulateMode);
                return true;
            }

            if (_simulateHardware)
            {
                _simulateMode = true;
                _logger.LogWarning("LightSettings:SimulateHardware=true — вспышка только программная задержка, COM не открывается");
                return true;
            }

            if (_inspectionFlashPorts.Length > 0)
            {
                _logger.LogInformation("InspectionFlashPorts: одновременная вспышка портов [{Ports}]", string.Join(',', _inspectionFlashPorts));
            }
            else if (_cameraPortMapping.Count == 0)
            {
                _logger.LogWarning("LightSettings:CameraPortMap пуст — задайте соответствие camera_id -> порт подсветки (например \"0\": 1)");
            }
            else
            {
                _logger.LogInformation("CameraPortMap: {Count} записей", _cameraPortMapping.Count);
            }

            int result = CIOControllerSDK.MV_IO_CreateHandle_CS(ref _handle);
            if (result != 0)
            {
                _logger.LogError("Ошибка создания handle: {Result} (0x{Result:X8})", result, result);
                if (_simulateIfInitFailed)
                {
                    _simulateMode = true;
                    _logger.LogWarning("MV_IO_CreateHandle не удался — включена симуляция вспышки (SimulateIfInitFailed=true)");
                    success = true;
                    goto LeaveLock;
                }
                success = false;
                goto LeaveLock;
            }

            var serial = new CIOControllerSDK.MV_IO_SERIAL();
            serial.strComName = _comPort;
            serial.nReserved = new uint[8];

            result = CIOControllerSDK.MV_IO_Open_CS(_handle, ref serial);
            if (result != 0)
            {
                _logger.LogError("Ошибка открытия {ComPort}: {Result} (0x{Result:X8})", _comPort, result, result);
                CIOControllerSDK.MV_IO_DestroyHandle_CS(_handle);
                _handle = IntPtr.Zero;
                if (_simulateIfInitFailed)
                {
                    _simulateMode = true;
                    _logger.LogWarning(
                        "Контроллер недоступен — включена симуляция вспышки (SimulateIfInitFailed=true). Для реального COM выставьте ComPort и SimulateIfInitFailed=false.");
                    success = true;
                    goto LeaveLock;
                }
                success = false;
                goto LeaveLock;
            }

            _isInitialized = true;
            _logger.LogInformation("Успешно подключено к {ComPort}", _comPort);
            turnOffAfterRelease = _turnOffAllOnInit;
            success = true;
        LeaveLock: ;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Ошибка при инициализации");
            if (_simulateIfInitFailed)
            {
                _simulateMode = true;
                _logger.LogWarning("Исключение при открытии COM — включена симуляция вспышки (SimulateIfInitFailed=true)");
                success = true;
            }
            else
            {
                success = false;
            }
        }
        finally
        {
            _lock.Release();
        }

        if (success && turnOffAfterRelease && !_simulateMode && _isInitialized)
            await TurnOffAllAsync();

        return success;
    }

    private async Task<bool> EnsureInitializedAsync()
    {
        if (_simulateMode || (_isInitialized && _handle != IntPtr.Zero))
            return true;

        const int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++)
        {
            if (await InitializeAsync() && (_simulateMode || (_isInitialized && _handle != IntPtr.Zero)))
                return true;
            if (attempt < maxAttempts)
                await Task.Delay(150);
        }
        return false;
    }

    public async Task<bool> SetLightAsync(int port, int brightness, int durationMs = 0)
    {
        if (_simulateMode)
        {
            _logger.LogDebug("Simulate SetLight port={Port} brightness={Brightness}", port, brightness);
            return true;
        }

        if (!await EnsureInitializedAsync())
        {
            _logger.LogWarning("Устройство не инициализировано");
            return false;
        }

        if (!_isInitialized || _handle == IntPtr.Zero)
        {
            _logger.LogWarning("Устройство не инициализировано");
            return false;
        }

        if (brightness < 0 || brightness > 100)
        {
            _logger.LogWarning("Некорректная яркость: {Brightness}", brightness);
            return false;
        }

        if (!_portMapping.TryGetValue(port, out byte portMask))
        {
            _logger.LogWarning("Некорректный номер порта: {Port}", port);
            return false;
        }

        await _lock.WaitAsync();
        try
        {
            var lightParam = new CIOControllerSDK.MV_IO_LIGHT_PARAM();
            lightParam.nPortNumber = portMask;
            lightParam.nLightValue = (ushort)brightness;
            lightParam.nLightState = 1; // Постоянное свечение
            lightParam.nLightEdge = 0;
            lightParam.nDurationTime = (ushort)durationMs;
            lightParam.nReserved = new uint[3];

            int result = CIOControllerSDK.MV_IO_SetLightParam_CS(_handle, ref lightParam);

            if (result != 0)
            {
                _logger.LogError("Ошибка установки параметров: {Result} (0x{Result:X8})", result, result);
                return false;
            }

            _logger.LogDebug("Порт {Port} установлен на яркость {Brightness}%", port, brightness);
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Ошибка при установке света");
            return false;
        }
        finally
        {
            _lock.Release();
        }
    }

    public async Task<bool> SetMultipleLightsAsync(byte brightness)
    {
        if (_simulateMode)
            return true;

        if (!_isInitialized || _handle == IntPtr.Zero)
        {
            return false;
        }

        await _lock.WaitAsync();
        int result = 0;

        try
        {
            foreach (var port in _portMapping.Values)
            {
                var lightParam = new CIOControllerSDK.MV_IO_LIGHT_PARAM();
                lightParam.nPortNumber = port;
                lightParam.nLightValue = brightness;
                lightParam.nLightState = 1;
                lightParam.nLightEdge = 0;
                lightParam.nDurationTime = 0;
                lightParam.nReserved = new uint[3];

                result += CIOControllerSDK.MV_IO_SetLightParam_CS(_handle, ref lightParam);
            }
        }
        finally
        {
            _lock.Release();
        }

        return result == 0;
    }

    public async Task<bool> FlashAsync(int port, int brightness, int durationMs)
    {
        if (_simulateMode)
        {
            int d = Math.Clamp(durationMs, 1, 5000);
            _logger.LogDebug("Simulate flash port={Port} brightness={Brightness} duration_ms={Duration}", port, brightness, d);
            await Task.Delay(d);
            return true;
        }

        int dMs = Math.Clamp(durationMs, 1, 5000);

        if (_flashHoldMode)
        {
            if (!await SetLightAsync(port, brightness))
                return false;
            await Task.Delay(dMs);
            return await SetLightAsync(port, 0);
        }

        if (!await EnsureInitializedAsync())
            return false;

        if (brightness <= 0)
        {
            _logger.LogWarning("Яркость 0 — импульс не послан");
            return false;
        }

        if (!_portMapping.TryGetValue(port, out byte portMask))
        {
            _logger.LogWarning("Некорректный номер порта вспышки: {Port}", port);
            return false;
        }

        await _lock.WaitAsync();
        try
        {
            var lightParam = new CIOControllerSDK.MV_IO_LIGHT_PARAM
            {
                nPortNumber = portMask,
                nLightValue = (ushort)Math.Clamp(brightness, 1, 100),
                nLightState = (ushort)CIOControllerSDK.MV_IO_LIGHTSTATE.MV_IO_LIGHTSTATE_TRIGGER,
                nLightEdge = (ushort)CIOControllerSDK.MV_IO_EDGE.MV_IO_EDGE_RISING,
                nDurationTime = (ushort)dMs,
                nReserved = new uint[3]
            };

            int result = CIOControllerSDK.MV_IO_SetLightParam_CS(_handle, ref lightParam);
            if (result != 0)
            {
                _logger.LogError("Ошибка импульса подсветки MV_IO_SetLightParam: {Result} (0x{Result:X8})", result, result);
                return false;
            }

            _logger.LogDebug("Импульс TRIGGER port={Port} mask=0x{Mask:X2} яркость={Brightness} длительность_ms={Duration}",
                port, portMask, brightness, dMs);
            await Task.Delay(dMs);
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Ошибка при импульсе подсветки");
            return false;
        }
        finally
        {
            _lock.Release();
        }
    }

    public async Task TurnOffAllAsync()
    {
        if (_simulateMode)
            return;

        await SetMultipleLightsAsync(0);
        _logger.LogInformation("Все порты выключены");
    }

    public bool IsConnected => _isInitialized && _handle != IntPtr.Zero;

    public async Task<bool> TriggerInspectionAsync(int cameraId, long frameId, string phase, int? brightness = null, int? durationMs = null)
    {
        int[] ports;
        if (_inspectionFlashPorts.Length > 0)
        {
            ports = _inspectionFlashPorts;
        }
        else if (!_cameraPortMapping.TryGetValue(cameraId, out var single))
        {
            _logger.LogWarning("Нет mapping camera_id={CameraId} -> port (и InspectionFlashPorts пуст)", cameraId);
            return false;
        }
        else
        {
            ports = new[] { single };
        }

        if (!_simulateMode && !await EnsureInitializedAsync())
        {
            _logger.LogWarning("Trigger skipped: controller not initialized camera_id={CameraId}", cameraId);
            return false;
        }

        int b = Math.Clamp(brightness ?? _defaultBrightness, 0, 100);
        int d = Math.Clamp(durationMs ?? _defaultDurationMs, 1, 5000);
        string portsLabel = string.Join(',', ports);
        _logger.LogInformation("Trigger light camera_id={CameraId} frame_id={FrameId} phase={Phase} ports=[{Ports}] brightness={Brightness} duration_ms={DurationMs}",
            cameraId, frameId, phase, portsLabel, b, d);
        return await FlashPortsAsync(ports, b, d);
    }

    /// <summary>Несколько портов: последовательные команды SDK (как на вашем контроллере; объединённая маска 0x01..0x08 ломала вспышку).</summary>
    private async Task<bool> FlashPortsAsync(int[] ports, int brightness, int durationMs)
    {
        if (ports.Length == 0)
            return false;
        if (ports.Length == 1)
            return await FlashAsync(ports[0], brightness, durationMs);

        if (_flashHoldMode)
            return await FlashHoldMultiplePortsAsync(ports, brightness, durationMs);

        _logger.LogWarning("Несколько портов при FlashMode=Trigger — последовательные импульсы");
        bool ok = true;
        foreach (int p in ports)
        {
            if (!await FlashAsync(p, brightness, durationMs))
                ok = false;
        }
        return ok;
    }

    /// <summary>Hold: порты включаются подряд (минимальный зазор), общая пауза, затем выключение всех.</summary>
    private async Task<bool> FlashHoldMultiplePortsAsync(int[] ports, int brightness, int durationMs)
    {
        if (_simulateMode)
        {
            int d = Math.Clamp(durationMs, 1, 5000);
            _logger.LogDebug("Simulate flash multi ports=[{Ports}] duration_ms={Duration}", string.Join(',', ports), d);
            await Task.Delay(d);
            return true;
        }

        foreach (int port in ports)
        {
            if (!await SetLightAsync(port, brightness))
                return false;
        }

        await Task.Delay(Math.Clamp(durationMs, 1, 5000));

        foreach (int port in ports)
        {
            if (!await SetLightAsync(port, 0))
                return false;
        }

        return true;
    }

    public async Task<Dictionary<int, int>> GetAllStatusAsync()
    {
        var status = new Dictionary<int, int>();

        if (!_isInitialized)
            return status;

        await _lock.WaitAsync();
        try
        {
            for (int i = 1; i <= 8; i++)
            {
                status[i] = 0;
            }
        }
        finally
        {
            _lock.Release();
        }

        return status;
    }

    public void Dispose()
    {
        if (_disposed) return;

        try
        {
            TurnOffAllAsync().Wait(1000);

            if (_handle != IntPtr.Zero)
            {
                if (_isInitialized)
                {
                    CIOControllerSDK.MV_IO_Close_CS(_handle);
                }
                CIOControllerSDK.MV_IO_DestroyHandle_CS(_handle);
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Ошибка при освобождении ресурсов");
        }
        finally
        {
            _handle = IntPtr.Zero;
            _isInitialized = false;
            _disposed = true;
            _lock.Dispose();
        }
    }
}