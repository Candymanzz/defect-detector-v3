namespace LightServer.Logging;

internal static class FileLogPaths
{
    private const string LogsFolderName = "logs";
    private const string ServiceFolderName = "lightserver";
    private const string ProjectMarkerFile = "LightServer.csproj";
    private const string EnvLogsDir = "LIGHTSERVER_LOGS_DIR";
    private const string EnvProjectRoot = "IML_PROJECT_ROOT";

    /// <summary>
    /// Папка логов: FileLogging:Directory → LIGHTSERVER_LOGS_DIR →
    /// &lt;repo&gt;/logs/lightserver → LightServer.v3/logs (legacy) → logs рядом с dll.
    /// </summary>
    public static string ResolveLogsDirectory(IConfiguration configuration, string contentRootPath)
    {
        string? fromConfig = configuration["FileLogging:Directory"];
        if (!string.IsNullOrWhiteSpace(fromConfig))
            return ToAbsolute(fromConfig.Trim(), contentRootPath);

        string? fromEnv = Environment.GetEnvironmentVariable(EnvLogsDir);
        if (!string.IsNullOrWhiteSpace(fromEnv))
            return Path.GetFullPath(fromEnv.Trim());

        string? repoLogs = TryFindRepoServiceLogsDirectory(contentRootPath)
            ?? TryFindRepoServiceLogsDirectory(AppContext.BaseDirectory)
            ?? TryFindRepoServiceLogsDirectory(Environment.GetEnvironmentVariable(EnvProjectRoot));
        if (repoLogs != null)
            return repoLogs;

        string? projectLogsFromContentRoot = TryFindProjectLogsDirectory(contentRootPath);
        if (projectLogsFromContentRoot != null)
            return projectLogsFromContentRoot;

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

    private static string? TryFindRepoServiceLogsDirectory(string? startDirectory)
    {
        if (string.IsNullOrWhiteSpace(startDirectory))
            return null;

        DirectoryInfo? dir = new DirectoryInfo(startDirectory);
        while (dir != null)
        {
            bool looksLikeRepo =
                Directory.Exists(Path.Combine(dir.FullName, "orchestrator-java"))
                && Directory.Exists(Path.Combine(dir.FullName, "analisSurface"));
            if (looksLikeRepo || File.Exists(Path.Combine(dir.FullName, ".git"))
                || Directory.Exists(Path.Combine(dir.FullName, ".git")))
            {
                if (looksLikeRepo
                    || (Directory.Exists(Path.Combine(dir.FullName, "LightServer.v3"))
                        && Directory.Exists(Path.Combine(dir.FullName, "analisSurface"))))
                {
                    return Path.Combine(dir.FullName, LogsFolderName, ServiceFolderName);
                }
            }

            dir = dir.Parent;
        }

        return null;
    }

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
