using System.Reflection;
using LightServer;
using LightServer.Services;
using Microsoft.Extensions.Options;

var builder = WebApplication.CreateBuilder(args);

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
builder.Services.AddSingleton<ComLightBankService>();
builder.Services.AddSingleton<IoControllerComService>();
builder.Services.AddHostedService<MvsSdkLifetime>();
builder.Services.AddHostedService<ComLightBankHostedService>();

var app = builder.Build();

app.UseSwagger();
app.UseSwaggerUI(options =>
{
    options.SwaggerEndpoint("/swagger/v1/swagger.json", "LightServer v1");
    options.RoutePrefix = "swagger";
});

app.UseAuthorization();
app.MapControllers();

app.Run();
