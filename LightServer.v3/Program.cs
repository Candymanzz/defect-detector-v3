using System.Reflection;
using LightServer;
using LightServer.Logging;
using LightServer.Services;
using Microsoft.Extensions.Options;

// Оркестратор: cwd = корень репо, dotnet exec …/LightServer.dll — без этого appsettings читается не из bin/.
var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    ContentRootPath = AppContext.BaseDirectory,
});

string logsDir = FileLogPaths.ResolveLogsDirectory(builder.Configuration, builder.Environment.ContentRootPath);
Directory.CreateDirectory(logsDir);
string logFilePath = FileLogPaths.CreateSessionLogFilePath(logsDir);
builder.Logging.AddProvider(new FileSessionLoggerProvider(logFilePath, builder.Configuration));

// Оркестратор наследует stdout — путь к логу виден сразу в его консоли.
Console.WriteLine($"[LightServer] file log: {logFilePath}");

builder.Services.Configure<SerialLightOptions>(builder.Configuration.GetSection(SerialLightOptions.SectionName));
builder.Services.Configure<ComLightDevicesOptions>(builder.Configuration.GetSection(ComLightDevicesOptions.SectionName));
builder.Services.AddSingleton<IPostConfigureOptions<ComLightDevicesOptions>, ComLightDevicesOptionsPostConfigure>();
builder.Services.Configure<IoControllerOptions>(builder.Configuration.GetSection(IoControllerOptions.SectionName));
builder.Services.AddControllers();
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen(options =>
{
    options.SwaggerDoc("v1", new()
    {
        Title = "LightServer API",
        Version = "v1",
        Description = "COM-подсветка: POST /api/com/light { state: on|off, brightness: \"100,...\" }."
    });

    string xmlPath = Path.Combine(AppContext.BaseDirectory, $"{Assembly.GetExecutingAssembly().GetName().Name}.xml");
    if (File.Exists(xmlPath))
        options.IncludeXmlComments(xmlPath);
});

builder.Services.AddSingleton<MvLeSerialLightSessions>();
builder.Services.AddSingleton<LightControlService>();
builder.Services.AddSingleton<ComLightIsolatedBank>();
builder.Services.AddSingleton<ComLightBankService>();
builder.Services.AddSingleton<IoControllerComService>();
builder.Services.AddHostedService<MvsSdkLifetime>();
builder.Services.AddHostedService<ComLightBankHostedService>();

var app = builder.Build();

ComLightDevicesOptions comDevices = app.Services.GetRequiredService<IOptions<ComLightDevicesOptions>>().Value;
app.Logger.LogInformation(
    "Content root: {ContentRoot}, ComLightDevices: {DeviceCount}, файл лога: {LogFile}",
    app.Environment.ContentRootPath,
    comDevices.Devices.Length,
    logFilePath);
if (comDevices.Devices.Length == 0)
    app.Logger.LogWarning("ComLightDevices:Devices пуст — проверьте appsettings.json рядом с {DllDir}", AppContext.BaseDirectory);

app.UseMiddleware<HttpExchangeLoggingMiddleware>();

app.UseSwagger();
app.UseSwaggerUI(options =>
{
    options.SwaggerEndpoint("/swagger/v1/swagger.json", "LightServer v1");
    options.RoutePrefix = "swagger";
});

app.UseAuthorization();
app.MapControllers();

app.Run();
