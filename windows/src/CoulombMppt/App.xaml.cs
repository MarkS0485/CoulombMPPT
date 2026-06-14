using System.Windows;
using System.Windows.Threading;
using CoulombMppt.Services;

namespace CoulombMppt;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // Anything that escapes a Dispatcher invocation (button click, bound
        // async command, etc.) lands here. We log + show a message box instead
        // of letting WPF kill the process.
        DispatcherUnhandledException += (s, args) =>
        {
            Log.E("crash", $"Dispatcher: {args.Exception}");
            MessageBox.Show(
                args.Exception.Message + "\n\nFull stack in %APPDATA%\\CoulombMppt\\logs\\log.txt",
                "Unexpected error",
                MessageBoxButton.OK, MessageBoxImage.Error);
            args.Handled = true;
        };

        AppDomain.CurrentDomain.UnhandledException += (s, args) =>
        {
            Log.E("crash", $"AppDomain: {args.ExceptionObject}");
        };

        TaskScheduler.UnobservedTaskException += (s, args) =>
        {
            Log.E("crash", $"UnobservedTask: {args.Exception}");
            args.SetObserved();
        };

        ServiceLocator.Init();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        ServiceLocator.Dispose();
        base.OnExit(e);
    }
}
