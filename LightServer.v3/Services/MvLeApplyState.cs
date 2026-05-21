namespace LightServer.Services;

/// <summary>Последнее применённое состояние MV-LE (для пропуска повторных POST и On без перезаписи яркости).</summary>
public sealed class MvLeApplyState
{
    private int[] _channels = [];
    private int[] _brightness = [];
    private string _source = "";
    private string _armedSource = "";

    public bool IsHardwareArmed =>
        _channels.Length > 0
        && !string.IsNullOrEmpty(_armedSource)
        && _armedSource.Equals("Timer1", StringComparison.OrdinalIgnoreCase);

    public bool IsRedundant(int[] channels, string source, int[] brightness, bool writeBrightness)
    {
        if (_channels.Length == 0 || !string.Equals(_source, source, StringComparison.Ordinal))
            return false;

        if (!SequenceEqual(_channels, channels))
            return false;

        return !writeBrightness || SequenceEqual(_brightness, brightness);
    }

    public bool CanSkipBrightness(int[] channels, int[] brightness) =>
        _channels.Length > 0 && SequenceEqual(_channels, channels) && SequenceEqual(_brightness, brightness);

    public void Update(int[] channels, string source, int[] brightness, string? armedSource = null)
    {
        _channels = (int[])channels.Clone();
        _source = source;
        _brightness = (int[])brightness.Clone();
        if (source.Equals("Off", StringComparison.OrdinalIgnoreCase))
            _armedSource = "";
        else if (!string.IsNullOrWhiteSpace(armedSource))
            _armedSource = armedSource;
    }

    public bool WasOff =>
        _source.Equals("Off", StringComparison.OrdinalIgnoreCase);

    /// <summary>Off: яркость помним, Timer1-arm сбрасываем — следующий On снова assemble+trigger.</summary>
    public void RecordOffKeepingArm(int[] channels)
    {
        _channels = (int[])channels.Clone();
        _source = "Off";
        _armedSource = "";
    }

    public void MarkArmed(int[] channels, int[] brightness, string armedSource) =>
        Update(channels, armedSource, brightness, armedSource);

    private static bool SequenceEqual(int[] a, int[] b)
    {
        if (a.Length != b.Length)
            return false;

        for (int i = 0; i < a.Length; i++)
        {
            if (a[i] != b[i])
                return false;
        }

        return true;
    }
}
