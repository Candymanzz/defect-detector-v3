using LightServer.Configuration;
using LightServer.Services;

namespace LightServer.Tests;

public class LightConfigLoaderTests
{
    [Theory]
    [InlineData(2, new[] { 1, 2 })]
    [InlineData(6, new[] { 1, 2, 3, 4 })]
    public void ParseDeviceChannels_expandsCount(int count, int[] expected) =>
        Assert.Equal(expected, LightConfigLoader.ParseDeviceChannels(count));

    [Fact]
    public void ParseDeviceChannels_readsExplicitList() =>
        Assert.Equal([1, 3], LightConfigLoader.ParseDeviceChannels(new object[] { 1, 3 }));

    [Theory]
    [InlineData(2, new[] { 2 })]
    [InlineData(new[] { 1, 2 }, new[] { 1, 2 })]
    public void ParseRouteChannels_handlesScalarAndList(object raw, int[] expected) =>
        Assert.Equal(expected, LightConfigLoader.ParseRouteChannels(raw));

    [Fact]
    public void ParseFile_readsDevicesAndRoutes()
    {
        string path = Path.Combine(Path.GetTempPath(), $"light-hw-{Guid.NewGuid():N}.yaml");
        File.WriteAllText(path, """
            light_hardware:
              initialize_on_startup: false
              devices:
                - id: bank-a
                  enabled: true
                  type: com
                  com_port: COM3
                  channels: 2
              camera_routes:
                - camera_number: 1
                  device_id: bank-a
                  channels: [1, 2]
            """);

        try
        {
            LightHardwareOptions options = LightConfigLoader.ParseFile(path);

            Assert.False(options.InitializeOnStartup);
            Assert.Single(options.Devices);
            Assert.Equal("bank-a", options.Devices[0].Id);
            Assert.Equal([1, 2], options.Devices[0].Channels);
            Assert.Single(options.CameraRoutes);
            Assert.Equal(1, options.CameraRoutes[0].CameraNumber);
            Assert.Equal([1, 2], options.CameraRoutes[0].Channels);
        }
        finally
        {
            File.Delete(path);
        }
    }
}

public class MvsComPortEnumeratorTests
{
    [Theory]
    [InlineData("COM3", "COM3")]
    [InlineData("3", "COM3")]
    [InlineData("COM_Port#COM5", "COM5")]
    [InlineData("com7", "COM7")]
    public void NormalizeComPort_standardizesInput(string raw, string expected) =>
        Assert.Equal(expected, MvsComPortEnumerator.NormalizeComPort(raw));
}

public class MvLeApplyStateTests
{
    [Fact]
    public void IsRedundant_detectsRepeatedState()
    {
        var state = new MvLeApplyState();
        state.Update([1, 2], "Timer1", [80, 90]);

        Assert.True(state.IsRedundant([1, 2], "Timer1", [80, 90], true));
        Assert.False(state.IsRedundant([1, 2], "Timer1", [70, 90], true));
    }

    [Fact]
    public void RecordOffKeepingArm_clearsArmedFlag()
    {
        var state = new MvLeApplyState();
        state.MarkArmed([1], [50], "Timer1");
        state.RecordOffKeepingArm([1]);

        Assert.True(state.WasOff);
        Assert.False(state.IsHardwareArmed);
    }
}
