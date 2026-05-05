using NsIOControllerSDK;

namespace LightServer.Services;

public class LightService : IDisposable
{
    private readonly ILogger<LightService> _logger;
    private IntPtr _handle;
    private bool _isInitialized;
    private readonly string _comPort;
    private readonly SemaphoreSlim _lock = new SemaphoreSlim(1, 1);
    private bool _disposed;

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
        _handle = IntPtr.Zero;
        _isInitialized = false;
    }

    public async Task<bool> InitializeAsync()
    {
        return await Task.Run(() => Initialize());
    }

    private async Task<bool> Initialize()
    {
        _lock.Wait();
        try
        {
            if (_isInitialized)
            {
                _logger.LogInformation("Устройство уже инициализировано");
                return true;
            }

            int result = CIOControllerSDK.MV_IO_CreateHandle_CS(ref _handle);
            if (result != 0)
            {
                _logger.LogError("Ошибка создания handle: {Result} (0x{Result:X8})", result, result);
                return false;
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
                return false;
            }

            _isInitialized = true;
            _logger.LogInformation("Успешно подключено к {ComPort}", _comPort);

            await TurnOffAllAsync();

            return true;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Ошибка при инициализации");
            return false;
        }
    }

    public async Task<bool> SetLightAsync(int port, int brightness, int durationMs = 0)
    {
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
    }

    public async Task<bool> SetMultipleLightsAsync(byte brightness)
    {
        if (!_isInitialized || _handle == IntPtr.Zero)
        {
            return false;
        }

        int result = 0;

        foreach (var port in _portMapping.Values){
            var lightParam = new CIOControllerSDK.MV_IO_LIGHT_PARAM();
            lightParam.nPortNumber = port;
            lightParam.nLightValue = brightness;
            lightParam.nLightState = 1;
            lightParam.nLightEdge = 0;
            lightParam.nDurationTime = 0;
            lightParam.nReserved = new uint[3];

            result += CIOControllerSDK.MV_IO_SetLightParam_CS(_handle, ref lightParam);
        }

        return result == 0;
    }

    public async Task<bool> FlashAsync(int port, int brightness, int durationMs)
    {
        if (!await SetLightAsync(port, brightness))
            return false;

        await Task.Delay(durationMs);

        return await SetLightAsync(port, 0);
    }

    public async Task TurnOffAllAsync()
    {
        await SetMultipleLightsAsync(0);
        _logger.LogInformation("Все порты выключены");
    }

    public bool IsConnected => _isInitialized && _handle != IntPtr.Zero;

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