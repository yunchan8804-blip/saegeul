using System.Threading;
using System.Windows;

namespace SaegeulAiCompanionTray;

public partial class App : System.Windows.Application
{
    private Mutex? _singleInstance;
    private TrayController? _tray;

    protected override void OnStartup(System.Windows.StartupEventArgs e)
    {
        base.OnStartup(e);
        _singleInstance = new Mutex(
            initiallyOwned: true,
            name: @"Local\SaegeulAiCompanionTray",
            createdNew: out var createdNew
        );
        if (!createdNew)
        {
            Shutdown();
            return;
        }

        try
        {
            var options = CompanionOptions.Parse(e.Args);
            _tray = new TrayController(options, Dispatcher, Shutdown);
            _tray.Start();
        }
        catch (Exception exception)
        {
            System.Windows.MessageBox.Show(
                exception.Message,
                "Saegeul AI",
                MessageBoxButton.OK,
                MessageBoxImage.Error
            );
            Shutdown(2);
        }
    }

    protected override void OnExit(System.Windows.ExitEventArgs e)
    {
        _tray?.Dispose();
        _singleInstance?.Dispose();
        base.OnExit(e);
    }
}
