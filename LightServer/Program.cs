using LightServer.Services;

// Оркестратор стартует с WorkingDirectory = корень репо; иначе appsettings.json не находится и CameraPortMap пустой.
var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    ContentRootPath = AppContext.BaseDirectory,
});

builder.Services.AddControllers();
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();
builder.Services.AddSingleton<LightService>();
builder.Services.AddCors(options =>
{
    options.AddPolicy("AllowAll", policy =>
    {
        policy.AllowAnyOrigin()
              .AllowAnyMethod()
              .AllowAnyHeader();
    });
});

var app = builder.Build();

if (app.Environment.IsDevelopment())
{
    app.UseSwagger();
    app.UseSwaggerUI();
}

app.UseCors("AllowAll");
app.UseAuthorization();
app.MapControllers();

var lightService = app.Services.GetRequiredService<LightService>();
await lightService.InitializeAsync();

var lifetime = app.Services.GetRequiredService<IHostApplicationLifetime>();
lifetime.ApplicationStopping.Register(() =>
{
    try
    {
        lightService.TurnOffAllAsync().GetAwaiter().GetResult();
    }
    catch
    {
        // best-effort при завершении процесса
    }
});

app.Run();