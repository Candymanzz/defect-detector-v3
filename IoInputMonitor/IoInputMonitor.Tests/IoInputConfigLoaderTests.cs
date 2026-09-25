using System.Text;
using System.Text.Json;
using Xunit;

namespace IoInputMonitor.Tests;

public class IoInputConfigLoaderTests
{
    [Theory]
    [InlineData("timer", IoCaptureStrategyKind.Timer)]
    [InlineData("software", IoCaptureStrategyKind.Timer)]
    [InlineData("direct", IoCaptureStrategyKind.Direct)]
    [InlineData("do", IoCaptureStrategyKind.Direct)]
    [InlineData("hardware", IoCaptureStrategyKind.Hardware)]
    [InlineData("hw", IoCaptureStrategyKind.Hardware)]
    [InlineData("listen", IoCaptureStrategyKind.Hardware)]
    [InlineData(null, IoCaptureStrategyKind.Timer)]
    public void ParseCaptureStrategy_mapsValues(string? raw, IoCaptureStrategyKind expected) =>
        Assert.Equal(expected, IoCaptureStrategyFactory.Parse(raw));

    [Fact]
    public void ParseFile_captureStrategyTimer_forcesTimerModeAndIndex5()
    {
        string path = Path.Combine(Path.GetTempPath(), $"io-cap-{Guid.NewGuid():N}.yaml");
        File.WriteAllText(path, """
            io_input:
              com_port: COM3
              inputs: [1, 2, 3]
              capture:
                enabled: true
                capture_strategy: timer
                output_mode: direct
                timer_index: 5
                pulse_repeat: 1
            """);
        try
        {
            IoInputOptions options = IoInputConfigLoader.ParseFile(path);
            Assert.Equal(IoCaptureStrategyKind.Timer, options.Capture.Strategy);
            Assert.Equal(IoCaptureOutputMode.Timer, options.Capture.OutputMode);
            Assert.Equal(5, options.Capture.TimerIndex);
            Assert.True(IoCaptureStrategyFactory.Create(options.Capture).FiresSoftwareDo);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public void ParseFile_captureStrategyHardware_doesNotFireSoftwareDo()
    {
        string path = Path.Combine(Path.GetTempPath(), $"io-hw-{Guid.NewGuid():N}.yaml");
        File.WriteAllText(path, """
            io_input:
              com_port: COM3
              inputs: [1, 2, 3]
              capture:
                enabled: true
                capture_strategy: hardware
            """);
        try
        {
            IoInputOptions options = IoInputConfigLoader.ParseFile(path);
            Assert.Equal(IoCaptureStrategyKind.Hardware, options.Capture.Strategy);
            Assert.False(IoCaptureStrategyFactory.Create(options.Capture).FiresSoftwareDo);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public void ParseFile_captureStrategyDirect_forcesDirectMode()
    {
        string path = Path.Combine(Path.GetTempPath(), $"io-direct-{Guid.NewGuid():N}.yaml");
        File.WriteAllText(path, """
            io_input:
              com_port: COM3
              inputs: [1, 2, 3, 5]
              capture:
                enabled: true
                capture_strategy: direct
                output_mode: timer
                channels:
                  - trigger_port: 3
                    output_port: 5
                  - trigger_port: 5
                    output_port: 5
            """);
        try
        {
            IoInputOptions options = IoInputConfigLoader.ParseFile(path);
            Assert.Equal(IoCaptureStrategyKind.Direct, options.Capture.Strategy);
            Assert.Equal(IoCaptureOutputMode.Direct, options.Capture.OutputMode);
            Assert.Equal(new[] { 3, 5 }, options.Capture.ResolveTriggerPorts());
            Assert.Equal(new[] { 5 }, options.Capture.ResolveOutputPorts());
            Assert.True(IoCaptureStrategyFactory.Create(options.Capture).FiresSoftwareDo);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Theory]
    [InlineData("timer", IoCaptureOutputMode.Timer)]
    [InlineData("software", IoCaptureOutputMode.Timer)]
    [InlineData("direct", IoCaptureOutputMode.Direct)]
    [InlineData("do", IoCaptureOutputMode.Direct)]
    [InlineData("auto", IoCaptureOutputMode.Auto)]
    [InlineData(null, IoCaptureOutputMode.Auto)]
    public void ParseOutputMode_mapsValues(string? raw, IoCaptureOutputMode expected) =>
        Assert.Equal(expected, IoInputConfigLoader.ParseOutputMode(raw));

    [Theory]
    [InlineData("rising", IoInputEdgeMode.Rising)]
    [InlineData("falling", IoInputEdgeMode.Falling)]
    [InlineData("both", IoInputEdgeMode.Both)]
    [InlineData(null, IoInputEdgeMode.Rising)]
    [InlineData("unknown", IoInputEdgeMode.Rising)]
    public void ParseEdgeMode_mapsValues(string? raw, IoInputEdgeMode expected) =>
        Assert.Equal(expected, IoInputConfigLoader.ParseEdgeMode(raw));

    [Theory]
    [InlineData("json", IoInputUdpPayloadFormat.Json)]
    [InlineData("byte", IoInputUdpPayloadFormat.Byte)]
    [InlineData("text_di", IoInputUdpPayloadFormat.TextDi)]
    [InlineData("byte_di", IoInputUdpPayloadFormat.ByteDi)]
    [InlineData("ascii", IoInputUdpPayloadFormat.Text)]
    public void ParseUdpFormat_mapsValues(string raw, IoInputUdpPayloadFormat expected) =>
        Assert.Equal(expected, IoInputConfigLoader.ParseUdpFormat(raw));

    [Fact]
    public void ParseInputPorts_filtersInvalidAndDuplicates()
    {
        int[] ports = IoInputConfigLoader.ParseInputPorts([3, 3, 0, 9, 1, 8]);

        Assert.Equal([3, 1, 8], ports);
    }

    [Fact]
    public void ParseFile_readsYamlSection() 
    {
        string path = Path.Combine(Path.GetTempPath(), $"io-input-{Guid.NewGuid():N}.yaml");
        File.WriteAllText(path, """
            io_input:
              com_port: COM5
              inputs: [1, 2]
              edge: both
              debounce_ms: 25
              publish:
                udp:
                  enabled: true
                  host: 10.0.0.2
                  port: 9200
                  format: text_di
                  inputs: [2]
            """);

        try
        {
            IoInputOptions options = IoInputConfigLoader.ParseFile(path);

            Assert.Equal("COM5", options.ComPort);
            Assert.Equal(IoInputEdgeMode.Both, options.EdgeMode);
            Assert.Equal(25, options.DebounceMs);
            Assert.True(options.UdpPublish.Enabled);
            Assert.Equal("10.0.0.2", options.UdpPublish.Host);
            Assert.Equal(9200, options.UdpPublish.Port);
            Assert.Equal(IoInputUdpPayloadFormat.TextDi, options.UdpPublish.Format);
            Assert.Equal([2], options.UdpPublish.PublishInputs);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public void ResolveExplicitPath_prefersEnvironmentVariable()
    {
        string path = Path.Combine(Path.GetTempPath(), "io-config.yaml");
        Environment.SetEnvironmentVariable(IoInputConfigLoader.ConfigEnvVar, path);
        try
        {
            Assert.Equal(path, IoInputConfigLoader.ResolveExplicitPath([]));
        }
        finally
        {
            Environment.SetEnvironmentVariable(IoInputConfigLoader.ConfigEnvVar, null);
        }
    }

    [Fact]
    public void ParseFile_readsCaptureChannels_di3AndDi5()
    {
        string path = Path.Combine(Path.GetTempPath(), $"io-channels-{Guid.NewGuid():N}.yaml");
        File.WriteAllText(path, """
            io_input:
              inputs: [1, 2, 3]
              capture:
                enabled: true
                capture_strategy: timer
                channels:
                  - trigger_port: 3
                    output_port: 5
                    timer_index: 5
                  - trigger_port: 5
                    output_port: 7
                    timer_index: 7
            """);

        try
        {
            IoInputOptions options = IoInputConfigLoader.ParseFile(path);

            Assert.True(options.Capture.Enabled);
            Assert.Equal([3, 5], options.Capture.ResolveTriggerPorts());
            Assert.Equal([5, 7], options.Capture.ResolveOutputPorts());
            Assert.Equal("DI3→DO5/T5, DI5→DO7/T7", options.Capture.FormatChannels());
            Assert.Contains(5, options.InputPorts);
            Assert.True(options.Capture.TryGetChannel(5, out IoCaptureChannel ch5));
            Assert.Equal(7, ch5.OutputPort);
            Assert.Equal(7, ch5.TimerIndex);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public void ParseFile_legacySingleChannel_fromTriggerAndOutput()
    {
        string path = Path.Combine(Path.GetTempPath(), $"io-capture-ports-{Guid.NewGuid():N}.yaml");
        File.WriteAllText(path, """
            io_input:
              capture:
                enabled: true
                trigger_port: 3
                output_port: 5
                pulse_duration_ms: 50
            """);

        try
        {
            IoInputOptions options = IoInputConfigLoader.ParseFile(path);

            Assert.True(options.Capture.Enabled);
            Assert.Equal(5, options.Capture.OutputPort);
            Assert.Equal([5], options.Capture.ResolveOutputPorts());
            Assert.Equal("DO5", options.Capture.FormatOutputPorts());
            Assert.Equal(50, options.Capture.PulseDurationMs);
            Assert.Single(options.Capture.ResolveChannels());
        }
        finally
        {
            File.Delete(path);
        }
    }
}

public class IoInputUdpPublisherTests
{
    [Fact]
    public void BuildPayload_jsonFormat()
    {
        byte[] payload = IoInputUdpPublisher.BuildPayload(IoInputUdpPayloadFormat.Json, 3, true);

        using JsonDocument doc = JsonDocument.Parse(Encoding.UTF8.GetString(payload));
        Assert.Equal(3, doc.RootElement.GetProperty("di").GetInt32());
        Assert.Equal(1, doc.RootElement.GetProperty("value").GetInt32());
    }

    [Fact]
    public void BuildPayload_textDiFormat()
    {
        byte[] payload = IoInputUdpPublisher.BuildPayload(IoInputUdpPayloadFormat.TextDi, 2, false);

        Assert.Equal("2:0", Encoding.UTF8.GetString(payload));
    }

    [Fact]
    public void BuildPayload_byteDiFormat()
    {
        byte[] payload = IoInputUdpPublisher.BuildPayload(IoInputUdpPayloadFormat.ByteDi, 4, true);

        Assert.Equal([4, 1], payload);
    }
}
