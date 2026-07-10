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

        Console.WriteLine(
            $"Мониторинг {inputsLabel} (edge={options.EdgeMode}, debounce={options.DebounceMs} мс). Ctrl+C для выхода.");

        var inputSet = new HashSet<int>(options.InputPorts);
        var portPressed = new Dictionary<int, bool>();
        var lastLoggedTicks = new Dictionary<(int Port, MvIoNative.IoEdgeType Edge), long>();
        object sessionLock = new();
        object consoleLock = new();

        foreach (int inputPort in options.InputPorts)
        {
            byte level = session.ReadInputLevel(inputPort);
            portPressed[inputPort] = level == (byte)MvIoNative.IoLevel.High;
            Console.WriteLine(
                $"[{Timestamp()}] DI{inputPort}: начальный уровень {DescribeLevel(level)} (raw={level})");
            PrintPortInputParam(session, inputPort);
        }

        uint debounceMs = (uint)options.DebounceMs;
        if (ShouldConfigureSdk(options))
        {
            foreach (int inputPort in options.InputPorts)
            {
                MvIoNative.IoEdgeType initialEdge = NextEdgeToArm(options.EdgeMode, portPressed[inputPort]);
                session.ConfigureInputEdge(inputPort, (uint)initialEdge, debounceMs);
            }

            if (options.EdgeMode == IoInputEdgeMode.Both)
            {
                Console.WriteLine(
                    "both: динамическое перевооружение фронта после каждого события " +
                    "(SDK поддерживает один фильтр на порт — это самый быстрый событийный режим).");
            }
        }
        else
        {
            Console.WriteLine(
                "configure_sdk=false — параметры порта не меняем, используем настройку устройства/MVS.");
        }

        using var udpPublisher = IoInputUdpPublisher.TryCreate(options.UdpPublish, options.InputPorts);
        if (udpPublisher != null && options.UdpPublish.SendInitialState)
        {
            int triggerPort = options.UdpPublish.TriggerPort;
            foreach (int inputPort in options.InputPorts)
            {
                if (!options.UdpPublish.SendInitialTriggerState && inputPort == triggerPort)
                    continue;

                udpPublisher.Publish(inputPort, portPressed[inputPort]);
            }
        }

        session.RegisterEdgeCallback((port, edge) =>
        {
            if (!inputSet.Contains(port))
                return;

            bool shouldLog = options.EdgeMode switch
            {
                IoInputEdgeMode.Both =>
                    TryTransitionPressed(portPressed, port, edge) &&
                    ShouldLogWithRefractory(lastLoggedTicks, port, edge, options.DebounceMs),
                IoInputEdgeMode.Rising when edge == MvIoNative.IoEdgeType.Rising =>
                    ShouldLogWithRefractory(lastLoggedTicks, port, edge, options.DebounceMs),
                IoInputEdgeMode.Falling when edge == MvIoNative.IoEdgeType.Falling =>
                    ShouldLogWithRefractory(lastLoggedTicks, port, edge, options.DebounceMs),
                _ => false
            };

            if (!shouldLog)
                return;

            string edgeName = edge switch
            {
                MvIoNative.IoEdgeType.Rising => "RISING",
                MvIoNative.IoEdgeType.Falling => "FALLING",
                _ => edge.ToString()
            };
            string action = edge switch
            {
                MvIoNative.IoEdgeType.Rising => "  <- замыкание (LOW -> HIGH)",
                MvIoNative.IoEdgeType.Falling => "  <- размыкание (HIGH -> LOW)",
                _ => ""
            };

            bool closed = options.EdgeMode == IoInputEdgeMode.Both
                ? portPressed[port]
                : edge == MvIoNative.IoEdgeType.Rising;

            lock (consoleLock)
            {
                string udpSuffix = udpPublisher != null ? $"  [udp {port}:{(closed ? 1 : 0)}]" : "";
                Console.WriteLine($"[{Timestamp()}] DI{port} edge {edgeName}{action}{udpSuffix}");
            }

            udpPublisher?.Publish(port, closed);

            if (options.EdgeMode == IoInputEdgeMode.Both && ShouldConfigureSdk(options))
            {
                bool pressed = portPressed.TryGetValue(port, out bool p) && p;
                MvIoNative.IoEdgeType nextEdge = NextEdgeToArm(IoInputEdgeMode.Both, pressed);
                int rearmPort = port;
                Task.Run(() => ReArmEdge(session, sessionLock, rearmPort, nextEdge, debounceMs));
            }
        });
        Console.WriteLine("Ожидаю фронты...");

        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            cts.Cancel();
        };

        cts.Token.WaitHandle.WaitOne();

        Console.WriteLine("Остановлено.");
        return 0;
    }

    private static bool ShouldLogWithRefractory(
        Dictionary<(int Port, MvIoNative.IoEdgeType Edge), long> lastLoggedTicks,
        int port,
        MvIoNative.IoEdgeType edge,
        int refractoryMs)
    {
        long now = Environment.TickCount64;
        var key = (port, edge);
        if (lastLoggedTicks.TryGetValue(key, out long last) && now - last < refractoryMs)
            return false;

        lastLoggedTicks[key] = now;
        return true;
    }

    private static bool TryTransitionPressed(
        Dictionary<int, bool> portPressed,
        int port,
        MvIoNative.IoEdgeType edge)
    {
        bool target = edge == MvIoNative.IoEdgeType.Rising;
        if (portPressed.TryGetValue(port, out bool current) && current == target)
            return false;

        portPressed[port] = target;
        return true;
    }

    private static MvIoNative.IoEdgeType NextEdgeToArm(IoInputEdgeMode edgeMode, bool pressed) =>
        edgeMode switch
        {
            IoInputEdgeMode.Falling => MvIoNative.IoEdgeType.Falling,
            IoInputEdgeMode.Both when pressed => MvIoNative.IoEdgeType.Falling,
            _ => MvIoNative.IoEdgeType.Rising
        };

    private static bool ShouldConfigureSdk(MonitorOptions options) =>
        options.EdgeMode == IoInputEdgeMode.Both || options.ConfigureSdk;

    private static void ReArmEdge(
        IoBoxSession session,
        object sessionLock,
        int port,
        MvIoNative.IoEdgeType edge,
        uint debounceMs)
    {
        lock (sessionLock)
        {
            if (!session.TryConfigureInputEdge(port, (uint)edge, debounceMs))
            {
                Console.Error.WriteLine(
                    $"[{Timestamp()}] DI{port}: не удалось перевооружить фронт {IoBoxSession.DescribeEdge((uint)edge)}");
            }
        }
    }

    private static void PrintPortInputParam(IoBoxSession session, int inputPort)
    {
        if (!session.TryReadPortInputParam(inputPort, out MvIoNative.MvIoSetInput param))
            return;

        string edge = IoBoxSession.DescribeEdge(param.Edge);
        string notice = param.Enable == 1 ? "вкл" : "выкл";
        Console.WriteLine(
            $"DI{inputPort}: устройство edge={edge}, уведомления={notice}, debounce={param.Glitch} мс");
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
            IoInputMonitor — события Digital Input с MV IO Box (MvIOInterfaceBox.dll)
            Мониторинг через edge callback SDK, без периодического опроса.

            Использование:
              IoInputMonitor
              IoInputMonitor --com COM3 --input 3
              IoInputMonitor --probe
              IoInputMonitor --com COM3 --scan
              IoInputMonitor --list

            Конфиг (по умолчанию config/blocks/52-io-input.yaml):
              com_port, inputs, edge (rising|falling|both), debounce_ms, configure_sdk
              rising — только замыкание (LOW→HIGH)
              falling — только размыкание (HIGH→LOW)
              both — оба фронта через динамическое перевооружение SDK (событийно, без polling)
              configure_sdk: false — не вызывать SetInput, использовать настройку MVS/устройства
              publish.udp — UDP 1/0 при смене состояния (1=замкнуто/HIGH, 0=разомкнуто/LOW)

            Параметры:
              --com COMx       COM-порт IO box (переопределяет конфиг)
              --input N        DI 1..8 (переопределяет inputs из конфига)
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
        public IoInputEdgeMode EdgeMode { get; set; } = IoInputEdgeMode.Rising;
        public bool ConfigureSdk { get; set; }
        public int DebounceMs { get; set; } = 50;
        public IoInputUdpPublishOptions UdpPublish { get; set; } = new();
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
                    case "--com":
                        options.ComPort = RequireValue(args, ref i, "--com");
                        break;
                    case "--input":
                        options.InputPorts = [int.Parse(RequireValue(args, ref i, "--input"))];
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
                EdgeMode = loaded.Options.EdgeMode,
                ConfigureSdk = loaded.Options.ConfigureSdk,
                DebounceMs = loaded.Options.DebounceMs,
                UdpPublish = loaded.Options.UdpPublish,
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

            if (options.DebounceMs is < 0 or > 1000)
                throw new ArgumentOutOfRangeException(nameof(options.DebounceMs), "debounce_ms должен быть 0..1000.");
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
