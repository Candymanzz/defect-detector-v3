using System.Reflection;
using LightServer;
using LightServer.Configuration;
using LightServer.Logging;
using LightServer.Services;
using Microsoft.Extensions.Options;

// Оркестратор: cwd = корень репо, dotnet exec …/LightServer.dll.
// ContentRootPath = папка exe (appsettings.json рядом с DLL); YAML ищется вверх от cwd и от exe.
var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    ContentRootPath = AppContext.BaseDirectory,
});

bool logEnabled = LightServerLogging.IsEnabled(builder.Configuration);
string? logFilePath = null;
if (logEnabled)
{
    string logsDir = FileLogPaths.ResolveLogsDirectory(builder.Configuration, builder.Environment.ContentRootPath);
    Directory.CreateDirectory(logsDir);
    logFilePath = FileLogPaths.CreateSessionLogFilePath(logsDir);
    builder.Logging.AddProvider(new FileSessionLoggerProvider(logFilePath, builder.Configuration));
    // Оркестратор наследует stdout — путь к логу виден сразу в его консоли.
    Console.WriteLine($"[LightServer] file log: {logFilePath}");
}
else
{
    builder.Logging.AddFilter(_ => false);
}

LightHardwareLoadResult hardwareLoad = LightConfigLoader.Load(args);
builder.Services.AddSingleton(hardwareLoad);
builder.Services.AddSingleton<LightHardwareRegistry>();

if (hardwareLoad.LoadedFromYaml && hardwareLoad.ConfigPath != null)
    Console.WriteLine($"[LightServer] light hardware config: {hardwareLoad.ConfigPath}");
else if (hardwareLoad.Warning != null)
    Console.WriteLine($"[LightServer] {hardwareLoad.Warning}");

builder.Services.Configure<SerialLightOptions>(builder.Configuration.GetSection(SerialLightOptions.SectionName));
builder.Services.Configure<ComLightDevicesOptions>(builder.Configuration.GetSection(ComLightDevicesOptions.SectionName));
builder.Services.AddSingleton<IPostConfigureOptions<ComLightDevicesOptions>, LightHardwareBindingPostConfigure>();
builder.Services.AddSingleton<IPostConfigureOptions<SerialLightOptions>, LightHardwareBindingPostConfigure>();
builder.Services.Configure<IoControllerOptions>(builder.Configuration.GetSection(IoControllerOptions.SectionName));
builder.Services.AddControllers();
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen(options =>
{
    options.SwaggerDoc("v1", new()
    {
        Title = "LightServer API",
        Version = "v1",
        Description = "Ethernet MV-LE bank: POST /api/camera-flash/bank { state: on|off }. COM: POST /api/com/light."
    });

    string xmlPath = Path.Combine(AppContext.BaseDirectory, $"{Assembly.GetExecutingAssembly().GetName().Name}.xml");
    if (File.Exists(xmlPath))
        options.IncludeXmlComments(xmlPath);
});

builder.Services.AddSingleton<MvLeSerialLightSessions>();
builder.Services.AddSingleton<LightControlService>();
builder.Services.AddSingleton<ComLightIsolatedBank>();
builder.Services.AddSingleton<ComLightBankService>();
builder.Services.AddSingleton<EthernetMvLeBank>();
builder.Services.AddSingleton<IoControllerComService>();
builder.Services.AddHostedService<MvsSdkLifetime>();
builder.Services.AddHostedService<ComLightBankHostedService>();
builder.Services.AddHostedService<EthernetMvLeBankHostedService>();

var app = builder.Build();

if (logEnabled)
{
    ComLightDevicesOptions comDevices = app.Services.GetRequiredService<IOptions<ComLightDevicesOptions>>().Value;
    app.Logger.LogInformation(
        "Content root: {ContentRoot}, ComLightDevices: {DeviceCount}, файл лога: {LogFile}",
        app.Environment.ContentRootPath,
        comDevices.Devices.Length,
        logFilePath);
    if (comDevices.Devices.Length == 0)
    {
        app.Logger.LogWarning(
            "COM-устройства не настроены — задайте config/blocks/51-light-hardware.yaml или ComLightDevices в appsettings.json ({DllDir})",
            AppContext.BaseDirectory);
    }

    app.UseMiddleware<HttpExchangeLoggingMiddleware>();
}

app.UseSwagger();
app.UseSwaggerUI(options =>
{
    options.SwaggerEndpoint("/swagger/v1/swagger.json", "LightServer v1");
    options.RoutePrefix = "swagger";
});

app.UseAuthorization();
app.MapControllers();

app.Lifetime.ApplicationStarted.Register(() =>
{
    string urls = app.Urls.Count > 0
        ? string.Join(", ", app.Urls)
        : (app.Configuration["ASPNETCORE_URLS"] ?? "http://localhost:5080");
    Console.WriteLine($"[LightServer] HTTP готов: {urls}  (swagger: /swagger)");
});

Console.WriteLine("[LightServer] Запуск Kestrel…");
app.Run();
