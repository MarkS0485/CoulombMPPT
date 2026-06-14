using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using QRCoder;
using CoulombMppt.Api;
using CoulombMppt.Data;
using CoulombMppt.Services;

namespace CoulombMppt.Ui.ViewModels;

// Remote-access control surface: start/stop the embedded HTTPS API, show how it
// can be reached (LAN IPs, UPnP, cert pin), and pair new clients via QR. Ported
// from the heater client's ApiServerViewModel; start/stop routes through
// ServiceLocator so it honours the same UPnP preference as the auto-start path.
public sealed partial class RemoteViewModel : ObservableObject
{
    private readonly ApiServer     _api      = ServiceLocator.Api;
    private readonly UpnpForwarder _upnp     = ServiceLocator.Upnp;
    private readonly AppSettings   _settings = ServiceLocator.Settings;
    private bool _syncing;

    [ObservableProperty] private bool        _running;
    [ObservableProperty] private int         _port = 8800;
    [ObservableProperty] private string      _certThumbprint = "";
    [ObservableProperty] private string      _upnpStatus = "Idle";
    [ObservableProperty] private string?     _publicAddress;
    [ObservableProperty] private string      _newClientLabel = "Phone";
    [ObservableProperty] private ImageSource? _pairingQr;
    [ObservableProperty] private string      _pairingUriPreview = "";
    [ObservableProperty] private string      _selectedHost = "";

    public ObservableCollection<string> HostChoices { get; } = new();
    public ObservableCollection<PairedClient> Clients { get; } = new();

    public RemoteViewModel()
    {
        _api.RunningChanged        += () => RunOnUi(SyncFromServer);
        _api.PairedClients.Changed += () => RunOnUi(RebuildClients);
        _settings.Changed          += () => RunOnUi(SyncFromServer);
        _upnp.StatusChanged        += () => RunOnUi(() =>
        {
            UpnpStatus = _upnp.Status;
            PublicAddress = _upnp.PublicAddress;
            RebuildHosts();
        });
        SyncFromServer();
        RebuildHosts();
        RebuildClients();
    }

    private void SyncFromServer()
    {
        _syncing = true;
        try
        {
            Running = _api.Running;
            Port = _api.Running ? _api.Port : _settings.ApiPort;
            CertThumbprint = _api.CertSha256;
        }
        finally { _syncing = false; }
    }

    partial void OnPortChanged(int value)
    {
        if (!_syncing) _settings.SetApiPort(value);
    }

    private void RebuildHosts()
    {
        var current = SelectedHost;
        HostChoices.Clear();
        foreach (var ip in ApiServer.EnumerateLanAddresses())
            HostChoices.Add(ip);
        if (!string.IsNullOrEmpty(PublicAddress) && !HostChoices.Contains(PublicAddress))
            HostChoices.Add(PublicAddress);
        if (HostChoices.Count == 0) HostChoices.Add("localhost");
        SelectedHost = (!string.IsNullOrEmpty(current) && HostChoices.Contains(current))
            ? current : HostChoices[0];
    }

    private void RebuildClients()
    {
        Clients.Clear();
        foreach (var c in _api.PairedClients.All) Clients.Add(c);
    }

    [RelayCommand]
    private async Task ToggleServer()
    {
        if (Running) await ServiceLocator.StopApiAsync();
        else         await ServiceLocator.StartApiAsync();
    }

    [RelayCommand]
    private void Pair()
    {
        var c = _api.PairedClients.CreatePending(NewClientLabel);
        var host = string.IsNullOrEmpty(SelectedHost) ? "localhost" : SelectedHost;
        var uri = _api.BuildPairingUri(c, host);
        PairingUriPreview = uri;
        PairingQr = MakeQr(uri);
    }

    [RelayCommand]
    private void Revoke(PairedClient? c) { if (c != null) _api.PairedClients.Revoke(c.KeyId); }

    [RelayCommand]
    private void Delete(PairedClient? c) { if (c != null) _api.PairedClients.Delete(c.KeyId); }

    private static BitmapImage MakeQr(string payload)
    {
        using var gen = new QRCodeGenerator();
        using var data = gen.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(8);
        var img = new BitmapImage();
        using var ms = new System.IO.MemoryStream(png);
        img.BeginInit();
        img.CacheOption = BitmapCacheOption.OnLoad;
        img.StreamSource = ms;
        img.EndInit();
        img.Freeze();
        return img;
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
