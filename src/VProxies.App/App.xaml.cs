using System.IO;
using System.Windows;
using System.Windows.Threading;

namespace VProxies;

public partial class App : System.Windows.Application
{
    private Mutex? _singleInstance;

    protected override void OnStartup(StartupEventArgs e)
    {
        _singleInstance = new Mutex(true, @"Local\VProxiesSA.Windows.SingleInstance", out var createdNew);
        if (!createdNew)
        {
            System.Windows.MessageBox.Show("VProxies SA is already running. Check the taskbar notification area.", "VProxies SA", MessageBoxButton.OK, MessageBoxImage.Information);
            Shutdown();
            return;
        }

        base.OnStartup(e);
        DispatcherUnhandledException += App_DispatcherUnhandledException;
        try
        {
            var licenseService = new LicenseService();
            var licenseStatus = licenseService.LoadInstalledLicense();
            if (!licenseStatus.IsValid)
            {
                var activation = new ActivationWindow(licenseService, licenseStatus.Message);
                MainWindow = activation;
                if (activation.ShowDialog() != true)
                {
                    Shutdown();
                    return;
                }
            }

            var window = new MainWindow(licenseService);
            MainWindow = window;
            window.Show();
        }
        catch (Exception ex)
        {
            ShowStartupFailure(ex);
            Shutdown(1);
        }
    }

    private void App_DispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        ShowStartupFailure(e.Exception);
        e.Handled = true;
        Shutdown(1);
    }

    private static void ShowStartupFailure(Exception exception)
    {
        var directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VProxiesSA");
        var logPath = Path.Combine(directory, "startup-crash.log");
        try
        {
            Directory.CreateDirectory(directory);
            File.AppendAllText(logPath, $"[{DateTimeOffset.Now:O}] VProxies SA startup failure\r\n{exception}\r\n\r\n");
        }
        catch { }
        System.Windows.MessageBox.Show($"VProxies SA could not start.\n\n{exception.Message}\n\nDiagnostic log: {logPath}", "VProxies SA Startup Error", MessageBoxButton.OK, MessageBoxImage.Error);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try { _singleInstance?.ReleaseMutex(); } catch (ApplicationException) { }
        _singleInstance?.Dispose();
        base.OnExit(e);
    }
}
