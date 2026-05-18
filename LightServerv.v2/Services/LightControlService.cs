using System.Globalization;
using LightServer.Models;
using MvCameraControl;

namespace LightServer.Services;

public sealed class LightControlService
{
    private const string BrightnessNode = "LightBrightness";
    private const int BrightnessMin = 0;
    private const int BrightnessMax = 255;
    private const int BrightnessDefaultOn = 255;

    private const DeviceTLayerType DevLayer =
        DeviceTLayerType.MvGigEDevice
        | DeviceTLayerType.MvUsbDevice
        | DeviceTLayerType.MvGenTLCameraLinkDevice
        | DeviceTLayerType.MvGenTLCXPDevice
        | DeviceTLayerType.MvGenTLXoFDevice;

    public (bool ok, string? error, DeviceListResponse? data) ListDevices()
    {
        int ret = DeviceEnumerator.EnumDevices(DevLayer, out List<IDeviceInfo> list);
        if (ret != MvError.MV_OK)
            return (false, $"EnumDevices failed: 0x{ret:x8}", null);

        var devices = new List<DeviceInfoDto>(list.Count);
        for (int i = 0; i < list.Count; i++)
        {
            IDeviceInfo d = list[i];
            devices.Add(new DeviceInfoDto
            {
                Index = i,
                TLayerType = d.TLayerType.ToString(),
                ModelName = d.ModelName ?? "",
                SerialNumber = d.SerialNumber ?? ""
            });
        }

        return (true, null, new DeviceListResponse { Count = devices.Count, Devices = devices });
    }

    /// <summary>Открыть MV-LE, применить source и яркость на каналах, закрыть.</summary>
    public (bool ok, string? error) SetLight(LightCommandRequest request)
    {
        string source = NormalizeSource(request.LightControllerSource);
        int[] channels = request.Channels is { Length: > 0 }
            ? request.Channels
            : [1, 2, 3, 4];

        if (request.Brightness is { Length: > 0 } && request.Brightness.Length != channels.Length)
            return (false, $"brightness length ({request.Brightness.Length}) must match channels length ({channels.Length}).");

        var (openOk, openErr, device) = OpenDevice(request.DeviceIndex);
        if (!openOk || device == null)
            return (false, openErr);

        try
        {
            if (!SupportsLightController(device))
                return (false, $"Device [{request.DeviceIndex}] is not a light controller (no LightControllerSelector).");

            bool hasBrightnessNode = SupportsBrightness(device);
            var appliedBrightness = new int[channels.Length];

            for (int i = 0; i < channels.Length; i++)
            {
                int ch = channels[i];
                if (ch is < 1 or > 4)
                    return (false, $"Invalid channel {ch}. Use 1–4.");

                int brightness = ResolveBrightness(request, i, source, hasBrightnessNode);
                appliedBrightness[i] = brightness;

                if (!ApplyChannel(device, ch, source, brightness, hasBrightnessNode))
                    return (false, $"Failed channel {ch}, source {source}, brightness {brightness}.");
            }

            string brightPart = hasBrightnessNode
                ? $", brightness [{string.Join(", ", appliedBrightness)}]"
                : "";

            return (true, $"Channels [{string.Join(", ", channels)}] -> {source}{brightPart}.");
        }
        finally
        {
            device.StreamGrabber.StopGrabbing();
            device.Close();
            device.Dispose();
        }
    }

    private static int ResolveBrightness(LightCommandRequest request, int index, string source, bool hasNode)
    {
        if (!hasNode)
            return 0;

        if (request.Brightness is { Length: > 0 })
            return ClampBrightness(request.Brightness[index]);

        return source == "On" ? BrightnessDefaultOn : 0;
    }

    private static int ClampBrightness(int value) =>
        Math.Clamp(value, BrightnessMin, BrightnessMax);

    private static (bool ok, string? error, IDevice? device) OpenDevice(int index)
    {
        int ret = DeviceEnumerator.EnumDevices(DevLayer, out List<IDeviceInfo> list);
        if (ret != MvError.MV_OK)
            return (false, $"EnumDevices: 0x{ret:x8}", null);

        if (index < 0 || index >= list.Count)
            return (false, $"Invalid device index: {index} (count {list.Count}).", null);

        IDevice device = DeviceFactory.CreateDevice(list[index]);
        ret = device.Open();
        if (ret != MvError.MV_OK)
        {
            device.Dispose();
            return (false, $"Open: 0x{ret:x8}", null);
        }

        if (device is IGigEDevice gige)
        {
            ret = gige.GetOptimalPacketSize(out int packetSize);
            if (packetSize > 0)
                device.Parameters.SetIntValue("GevSCPSPacketSize", packetSize);
        }

        return (true, null, device);
    }

    private static bool SupportsLightController(IDevice device) =>
        device.Parameters.GetEnumValue("LightControllerSelector", out IEnumValue _) == MvError.MV_OK;

    private static bool SupportsBrightness(IDevice device) =>
        device.Parameters.GetIntValue(BrightnessNode, out IIntValue _) == MvError.MV_OK;

    private static bool ApplyChannel(IDevice device, int channel, string source, int brightness, bool setBrightness)
    {
        string selector = channel.ToString(CultureInfo.InvariantCulture);

        int ret = device.Parameters.SetEnumValueByString("LightControllerSelector", selector);
        if (ret != MvError.MV_OK)
            ret = device.Parameters.SetEnumValue("LightControllerSelector", (uint)channel);
        if (ret != MvError.MV_OK)
            return false;

        if (setBrightness)
        {
            ret = device.Parameters.SetIntValue(BrightnessNode, brightness);
            if (ret != MvError.MV_OK)
                return false;
        }

        ret = device.Parameters.SetEnumValueByString("LightControllerSource", source);
        if (ret != MvError.MV_OK && TrySourceNumeric(source, out uint src))
            ret = device.Parameters.SetEnumValue("LightControllerSource", src);

        return ret == MvError.MV_OK;
    }

    private static string NormalizeSource(string source)
    {
        if (string.IsNullOrWhiteSpace(source))
            return "On";

        return source.Trim().ToUpperInvariant() switch
        {
            "ON" or "1" or "TRUE" => "On",
            "OFF" or "0" or "FALSE" => "Off",
            _ => source.Trim()
        };
    }

    private static bool TrySourceNumeric(string name, out uint value)
    {
        value = 0;
        switch (name.Trim().ToUpperInvariant())
        {
            case "ON": value = 1; return true;
            case "OFF": value = 255; return true;
            default: return false;
        }
    }
}
