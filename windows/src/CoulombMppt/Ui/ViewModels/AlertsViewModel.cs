using System.Collections.ObjectModel;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using CoulombMppt.Data;
using CoulombMppt.Services;

namespace CoulombMppt.Ui.ViewModels;

// Newest-first list of alert events from AlertStore, across all controllers,
// within the store's retention window. Lets the user dismiss individual alerts,
// dismiss everything still active, or clear the whole log.
public sealed partial class AlertsViewModel : ObservableObject
{
    private readonly AlertStore _alerts = ServiceLocator.Alerts;

    public AlertsViewModel()
    {
        _alerts.Changed += () => RunOnUi(Rebuild);
        Rebuild();
    }

    public ObservableCollection<AlertRecord> Alerts { get; } = new();

    [ObservableProperty] private bool   _hasAlerts;
    [ObservableProperty] private string _summary = "";

    [RelayCommand]
    private void Dismiss(AlertRecord? a)
    {
        if (a != null) _alerts.Dismiss(a.Id);
    }

    [RelayCommand]
    private void DismissAll()
    {
        // Store dismisses per controller; fan out over the macs currently shown.
        foreach (var mac in Alerts.Where(a => a.IsActive)
                                  .Select(a => a.ControllerMac)
                                  .Distinct()
                                  .ToArray())
            _alerts.DismissAllFor(mac);
    }

    [RelayCommand]
    private void Clear() => _alerts.Clear();

    private void Rebuild()
    {
        long since = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
                     - (long)AlertStore.RetentionDays * 24 * 60 * 60 * 1000;
        var rows = _alerts.RecentSince(since);

        Alerts.Clear();
        foreach (var a in rows) Alerts.Add(a);

        HasAlerts = Alerts.Count > 0;
        int active = Alerts.Count(a => a.IsActive);
        Summary = HasAlerts
            ? $"{Alerts.Count} in the last {AlertStore.RetentionDays} days · {active} active"
            : $"No alerts in the last {AlertStore.RetentionDays} days.";
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
