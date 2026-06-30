using System.IO.Ports;

namespace IoInputMonitor;

internal static class Program
{
    private static int Main(string[] args)
    {
        var options = MonitorOptions.Parse(args);
        if (options.ShowHelp)
        {
            PrintHelp();
            return 0;
        }

        if (options.ListPorts)
        {
            PrintHostComPorts();
            return 0;
        }

        if (options.ProbePorts)
        {
            RunProbe();
            return 0;
        }

        try
        {
            return RunMonitor(options);
        }
        catch (DllNotFoundException)
        {
            Console.Error.WriteLine("MvIOInterfaceBox.dll не найдена рядом с exe.");
            return 2;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(ex.Message);
            return 1;
        }
    }

    private static int RunMonitor(MonitorOptions options)
    {
        var sdkVersion = new MvIoNative.MvIoVersion { Reserved = new uint[8] };
        int sdkRet = MvIoNative.GetSdkVersion(ref sdkVersion);
        if (sdkRet == MvIoNative.MvOk)
        {
            Console.WriteLine(
                $"SDK {sdkVersion.Main}.{sdkVersion.Sub}.{sdkVersion.Modify} " +
                $"({sdkVersion.Year:D4}-{sdkVersion.Month:D2}-{sdkVersion.Day:D2})");
        }

        if (options.ConfigPath != null)
            Console.WriteLine($"Конфиг: {options.ConfigPath}");

        using var session = new IoBoxSession(options.ComPort);
        Console.WriteLine($"Открываю {options.ComPort}...");
        session.Open();
        Console.WriteLine($"Подключено: {session.OpenedComName}");

        string inputsLabel = string.Join(", ", options.InputPorts.Select(static p => $"DI{p}"));

        if (!session.TryReadFirmwareVersion(out MvIoNative.MvIoVersion firmware))
        {
            string? ioCom = IoBoxProbe.FindIoBoardComPort();
            throw new InvalidOperationException(
                $"{options.ComPort} открылся, но на нём нет IO box (MV-LE подсветка без DI). " +
                (ioCom != null
                    ? $"Используйте com_port: {ioCom} в конфиге или --com {ioCom}"
                    : "Запустите: dotnet run -- --probe"));
        }

        Console.WriteLine(
            $"Прошивка IO box {firmware.Main}.{firmware.Sub}.{firmware.Modify} " +
            $"({firmware.Year:D4}-{firmware.Month:D2}-{firmware.Day:D2})");

        if (options.ScanAll)
        {
            var all = session.ReadAllInputLevels();
            for (int port = 1; port <= 8; port++)
            {
                byte level = MvIoNative.ReadLevel(all, port);
                Console.WriteLine($"DI{port}: {DescribeLevel(level)} (raw={level})");
            }
            return 0;
        }

        Console.WriteLine($"Мониторинг {inputsLabel}. Ctrl+C для выхода.");
        Console.WriteLine("0 = LOW, 1 = HIGH. Нажатие кнопки обычно меняет уровень.");

        var inputSet = new HashSet<int>(options.InputPorts);

        if (options.UseEdgeCallback)
        {
            session.RegisterEdgeCallback((port, edge) =>
            {
                if (!inputSet.Contains(port))
                    return;

                string edgeName = edge switch
                {
                    MvIoNative.IoEdgeType.Rising => "RISING",
                    MvIoNative.IoEdgeType.Falling => "FALLING",
                    _ => edge.ToString()
                };
                Console.WriteLine($"[{Timestamp()}] DI{port} edge {edgeName}");
            });
            Console.WriteLine("Edge callback включён. Ожидаю фронты...");
        }

        var lastLevels = new Dictionary<int, int?>();
        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            cts.Cancel();
        };

        while (!cts.IsCancellationRequested)
        {
            foreach (int inputPort in options.InputPorts)
            {
                byte level = session.ReadInputLevel(inputPort);
                lastLevels.TryGetValue(inputPort, out int? lastLevel);
                if (lastLevel != level)
                {
                    string state = DescribeButtonTransition(lastLevel, level);
                    Console.WriteLine(
                        $"[{Timestamp()}] DI{inputPort}: {DescribeLevel(level)} (raw={level}){state}");
                    lastLevels[inputPort] = level;
                }
            }

            Thread.Sleep(options.PollIntervalMs);
        }

        Console.WriteLine("Остановлено.");
        return 0;
    }

    private static void RunProbe()
    {
        Console.WriteLine("Поиск IO box на COM-портах...");
        foreach (IoBoxProbe.ProbeResult result in IoBoxProbe.ScanAllPorts())
        {
            if (result.HasIoBoard)
                Console.WriteLine($"{result.ComPort}: IO box OK, прошивка {result.Firmware}");
            else if (result.Opened)
                Console.WriteLine($"{result.ComPort}: открыт, но не IO box — {result.Error}");
            else
                Console.WriteLine($"{result.ComPort}: не открылся — {result.Error}");
        }

        string? ioCom = IoBoxProbe.FindIoBoardComPort();
        if (ioCom != null)
            Console.WriteLine($"Рекомендуемый порт для DI: {ioCom}");
        else
            Console.WriteLine("IO box не найден ни на одном COM.");
    }

    private static string DescribeLevel(byte level) =>
        level switch
        {
            0 => "LOW",
            1 => "HIGH",
            _ => $"UNKNOWN({level})"
        };

    private static string DescribeButtonTransition(int? previous, byte current)
    {
        if (previous is null)
            return "";

        return (previous.Value, current) switch
        {
            (0, 1) => "  <- замыкание (LOW -> HIGH)",
            (1, 0) => "  <- размыкание (HIGH -> LOW)",
            _ => ""
        };
    }

    private static string Timestamp() =>
        DateTime.Now.ToString("HH:mm:ss.fff");

    private static void PrintHostComPorts()
    {
        string[] ports = SerialPort.GetPortNames()
            .OrderBy(static p => p, StringComparer.OrdinalIgnoreCase)
            .ToArray();

        if (ports.Length == 0)
        {
            Console.WriteLine("На ПК не видно COM-портов.");
            return;
        }

        Console.WriteLine("COM-порты Windows:");
        foreach (string port in ports)
            Console.WriteLine($"  {port}");
    }

    private static void PrintHelp()
    {
        Console.WriteLine(
            """
            IoInputMonitor — чтение Digital Input с MV IO Box (MvIOInterfaceBox.dll)

            Использование:
              IoInputMonitor
              IoInputMonitor --com COM3 --input 3
              IoInputMonitor --probe
              IoInputMonitor --com COM3 --scan
              IoInputMonitor --list

            Конфиг (по умолчанию config/blocks/52-io-input.yaml):
              com_port, poll_interval_ms, inputs: [3, ...]
              Переопределение: IO_INPUT_CONFIG или --io-config=путь

            Параметры:
              --com COMx       COM-порт IO box (переопределяет конфиг)
              --input N        DI 1..8 (переопределяет inputs из конфига)
              --poll MS        Интервал опроса, мс (переопределяет конфиг)
              --edge           Дополнительно слушать edge callback SDK
              --scan           Однократно показать DI1..DI8 и выйти
              --probe          Найти, на каком COM висит IO box с DI
              --list           Показать COM-порты Windows
              --help           Эта справка

            Примечания:
              - COM1/COM2 часто заняты MV-LE (подсветка), DI там нет.
              - IO box с DI обычно на отдельном COM (у вас COM3).
              - Закройте MVS Client / LightServer, если COM занят.
            """);
    }

    private sealed class MonitorOptions
    {
        public string ComPort { get; set; } = "COM3";
        public int[] InputPorts { get; set; } = [3];
        public int PollIntervalMs { get; set; } = 100;
        public bool UseEdgeCallback { get; set; }
        public bool ScanAll { get; set; }
        public bool ProbePorts { get; set; }
        public bool ListPorts { get; set; }
        public bool ShowHelp { get; set; }
        public string? ConfigPath { get; set; }

        public static MonitorOptions Parse(string[] args)
        {
            if (args.Length == 0)
                return FromConfig(args);

            var options = FromConfig(args);

            for (int i = 0; i < args.Length; i++)
            {
                string arg = args[i];
                switch (arg)
                {
                    case "--help":
                    case "-h":
                    case "/?":
                        return new MonitorOptions { ShowHelp = true };
                    case "--list":
                        return new MonitorOptions { ListPorts = true };
                    case "--scan":
                        options.ScanAll = true;
                        break;
                    case "--probe":
                        options.ProbePorts = true;
                        break;
                    case "--edge":
                        options.UseEdgeCallback = true;
                        break;
                    case "--com":
                        options.ComPort = RequireValue(args, ref i, "--com");
                        break;
                    case "--input":
                        options.InputPorts = [int.Parse(RequireValue(args, ref i, "--input"))];
                        break;
                    case "--poll":
                        options.PollIntervalMs = int.Parse(RequireValue(args, ref i, "--poll"));
                        break;
                    default:
                        if (arg.StartsWith(IoInputConfigLoader.ConfigCliPrefix, StringComparison.OrdinalIgnoreCase))
                            break;
                        throw new ArgumentException($"Неизвестный аргумент: {arg}");
                }
            }

            Validate(options);
            return options;
        }

        private static MonitorOptions FromConfig(string[] args)
        {
            IoInputConfigLoadResult loaded = IoInputConfigLoader.Load(args);
            return new MonitorOptions
            {
                ComPort = loaded.Options.ComPort,
                InputPorts = loaded.Options.InputPorts,
                PollIntervalMs = loaded.Options.PollIntervalMs,
                ConfigPath = loaded.ConfigPath
            };
        }

        private static void Validate(MonitorOptions options)
        {
            if (options.InputPorts.Length == 0)
                throw new ArgumentException("Список inputs пуст — укажите DI 1..8 в конфиге или --input N.");

            foreach (int port in options.InputPorts)
            {
                if (port is < 1 or > 8)
                    throw new ArgumentOutOfRangeException(nameof(options.InputPorts), "DI должен быть 1..8.");
            }

            if (options.PollIntervalMs < 10)
                throw new ArgumentOutOfRangeException(nameof(options.PollIntervalMs), "poll >= 10 мс.");
        }

        private static string RequireValue(string[] args, ref int index, string name)
        {
            if (index + 1 >= args.Length)
                throw new ArgumentException($"После {name} нужно значение.");

            index++;
            return args[index];
        }
    }
}
