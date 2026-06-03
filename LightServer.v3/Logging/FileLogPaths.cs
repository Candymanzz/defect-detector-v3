namespace LightServer.Logging;

internal static class FileLogPaths
{
    private const string LogsFolderName = "logs";
    private const string ProjectMarkerFile = "LightServer.csproj";
    private const string EnvLogsDir = "LIGHTSERVER_LOGS_DIR";

    /// <summary>
    /// Папка логов: FileLogging:Directory → env → LightServer.v3/logs (если найден .csproj вверх от bin) → logs рядом с dll.
    /// </summary>
    public static string ResolveLogsDirectory(IConfiguration configuration, string contentRootPath)
    {
        string? fromConfig = configuration["FileLogging:Directory"];
        if (!string.IsNullOrWhiteSpace(fromConfig))
            return ToAbsolute(fromConfig.Trim(), contentRootPath);

        string? projectLogsFromContentRoot = TryFindProjectLogsDirectory(contentRootPath);
        if (projectLogsFromContentRoot != null)
            return projectLogsFromContentRoot;

        string? fromEnv = Environment.GetEnvironmentVariable(EnvLogsDir);
        if (!string.IsNullOrWhiteSpace(fromEnv))
            return Path.GetFullPath(fromEnv.Trim());

        string? projectLogs = TryFindProjectLogsDirectory(AppContext.BaseDirectory);
        if (projectLogs != null)
            return projectLogs;

        return Path.Combine(AppContext.BaseDirectory, LogsFolderName);
    }

    public static string CreateSessionLogFilePath(string logsDirectory) =>
        Path.Combine(logsDirectory, $"{DateTime.Now:yyyy-MM-dd_HH-mm-ss}.log");

    private static string ToAbsolute(string path, string contentRootPath) =>
        Path.IsPathRooted(path)
            ? Path.GetFullPath(path)
            : Path.GetFullPath(Path.Combine(contentRootPath, path));

    private static string? TryFindProjectLogsDirectory(string startDirectory)
    {
        DirectoryInfo? dir = new DirectoryInfo(startDirectory);
        while (dir != null)
        {
            if (File.Exists(Path.Combine(dir.FullName, ProjectMarkerFile)))
                return Path.Combine(dir.FullName, LogsFolderName);

            dir = dir.Parent;
        }

        return null;
    }
}
