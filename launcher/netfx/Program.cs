using System;
using System.Windows.Forms;

namespace ImlLauncher
{
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            bool noFrontend = HasFlag(args, "--no-frontend") || HasFlag(args, "-NoFrontend");
            string configArg = GetOption(args, "--config") ?? GetOption(args, "-Config");

            string repoRoot;
            try
            {
                repoRoot = StartupController.ResolveRepoRoot();
            }
            catch (Exception ex)
            {
                MessageBox.Show(
                    ex.Message,
                    "Defect Detector",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
                return 1;
            }

            LaunchOptions options = new LaunchOptions();
            options.NoFrontend = noFrontend;
            options.ConfigArg = configArg;
            options.RepoRoot = repoRoot;

            ServiceStatusModel model = new ServiceStatusModel(!noFrontend);
            SplashForm form = new SplashForm(model);
            StartupController controller = new StartupController(options, model, form.RefreshFromModel);
            form.AttachController(controller);

            Application.Run(form);
            return string.IsNullOrEmpty(controller.FatalError) ? 0 : 1;
        }

        private static bool HasFlag(string[] args, string flag)
        {
            foreach (string a in args)
            {
                if (string.Equals(a, flag, StringComparison.OrdinalIgnoreCase))
                {
                    return true;
                }
            }
            return false;
        }

        private static string GetOption(string[] args, string name)
        {
            for (int i = 0; i < args.Length - 1; i++)
            {
                if (string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase))
                {
                    return args[i + 1];
                }
            }
            return null;
        }
    }
}
