using System.Text;
using System.Text.Json;
using Xunit;

namespace IoInputMonitor.Tests;

public class IoInputConfigLoaderTests
{
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
