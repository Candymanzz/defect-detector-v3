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

        using var session = new IoBoxSession(options.ComPort);
        Console.WriteLine($"Открываю {options.ComPort}...");
        session.Open();
        Console.WriteLine($"Подключено: {session.OpenedComName}");

        if (!session.TryReadFirmwareVersion(out MvIoNative.MvIoVersion firmware))
        {
            string? ioCom = IoBoxProbe.FindIoBoardComPort();
            throw new InvalidOperationException(
                $"{options.ComPort} открылся, но на нём нет IO box (MV-LE подсветка без DI). " +
                (ioCom != null
                    ? $"Используйте: dotnet run -- --com {ioCom} --input {options.InputPort}"
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

        Console.WriteLine($"Мониторинг DI{options.InputPort}. Ctrl+C для выхода.");
        Console.WriteLine("0 = LOW, 1 = HIGH. Нажатие кнопки обычно меняет уровень.");

        if (options.UseEdgeCallback)
        {
            session.RegisterEdgeCallback((port, edge) =>
            {
                if (port != options.InputPort)
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

        int? lastLevel = null;
        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            cts.Cancel();
        };

        while (!cts.IsCancellationRequested)
        {
            byte level = session.ReadInputLevel(options.InputPort);
            if (lastLevel != level)
            {
                string state = DescribeButtonTransition(lastLevel, level);
                Console.WriteLine(
                    $"[{Timestamp()}] DI{options.InputPort}: {DescribeLevel(level)} (raw={level}){state}");
                lastLevel = level;
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
              IoInputMonitor --com COM3 --input 3
              IoInputMonitor --probe
              IoInputMonitor --com COM3 --scan
              IoInputMonitor --list

            Параметры:
              --com COMx       COM-порт IO box (по умолчанию COM3)
              --input N        Номер DI 1..8 (по умолчанию 3)
              --poll MS        Интервал опроса, мс (по умолчанию 100)
              --edge           Дополнительно слушать edge callback SDK
              --scan           Однократно показать DI1..DI8 и выйти
              --probe          Найти, на каком COM висит IO box с DI
              --list           Показать COM-порты Windows
              --help           Эта справка

            Примечания:
              - COM1/COM2 часто заняты MV-LE (подсветка), DI там нет.
              - IO box с DI3 обычно на отдельном COM (у вас COM3).
              - Закройте MVS Client / LightServer, если COM занят.
            """);
    }

    private sealed class MonitorOptions
    {
        public string ComPort { get; set; } = "COM3";
        public int InputPort { get; set; } = 3;
        public int PollIntervalMs { get; set; } = 100;
        public bool UseEdgeCallback { get; set; }
        public bool ScanAll { get; set; }
        public bool ProbePorts { get; set; }
        public bool ListPorts { get; set; }
        public bool ShowHelp { get; set; }

        public static MonitorOptions Parse(string[] args)
        {
            if (args.Length == 0)
                return new MonitorOptions();

            var options = new MonitorOptions();
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
                        options.InputPort = int.Parse(RequireValue(args, ref i, "--input"));
                        break;
                    case "--poll":
                        options.PollIntervalMs = int.Parse(RequireValue(args, ref i, "--poll"));
                        break;
                    default:
                        throw new ArgumentException($"Неизвестный аргумент: {arg}");
                }
            }

            if (options.InputPort is < 1 or > 8)
                throw new ArgumentOutOfRangeException(nameof(options.InputPort), "DI должен быть 1..8.");

            if (options.PollIntervalMs < 10)
                throw new ArgumentOutOfRangeException(nameof(options.PollIntervalMs), "poll >= 10 мс.");

            return options;
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
