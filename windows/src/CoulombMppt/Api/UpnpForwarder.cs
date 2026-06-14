using Mono.Nat;
using CoulombMppt.Services;
using NatProto = Mono.Nat.Protocol;

namespace CoulombMppt.Api;

// Best-effort UPnP / NAT-PMP port forwarding. Lots of routers either don't
// support it or have it disabled — that's fine, we just log the outcome and let
// the user forward manually. The pairing UI surfaces which state we're in.
public sealed class UpnpForwarder
{
    private INatDevice? _device;
    private int _port;

    public string Status { get; private set; } = "Idle";
    public string? PublicAddress { get; private set; }
    public event Action? StatusChanged;

    public void Start(int port)
    {
        _port = port;
        Status = "Searching for UPnP / NAT-PMP device…";
        StatusChanged?.Invoke();
        try
        {
            NatUtility.DeviceFound -= OnFound;
            NatUtility.DeviceFound += OnFound;
            NatUtility.StartDiscovery();
        }
        catch (Exception ex) { Set($"Discovery failed: {ex.Message}"); }
    }

    public void Stop()
    {
        try
        {
            NatUtility.DeviceFound -= OnFound;
            NatUtility.StopDiscovery();
        }
        catch { }
        try
        {
            if (_device != null)
            {
                var m = new Mapping(NatProto.Tcp, _port, _port, 0, "CoulombMPPT API");
                _ = _device.DeletePortMapAsync(m);
            }
        }
        catch { }
        _device = null;
        Set("Idle");
        PublicAddress = null;
    }

    private async void OnFound(object? sender, DeviceEventArgs args)
    {
        var dev = args.Device;
        try
        {
            await dev.CreatePortMapAsync(new Mapping(NatProto.Tcp, _port, _port, 3600, "CoulombMPPT API"));
            _device = dev;
            try
            {
                var ext = await dev.GetExternalIPAsync();
                PublicAddress = ext?.ToString();
            }
            catch { /* not all routers expose this */ }
            Set($"Forwarded TCP {_port} → {PublicAddress ?? "(unknown WAN IP)"}");
            Log.I("upnp", Status);
        }
        catch (Exception ex)
        {
            Set($"Mapping failed: {ex.Message}");
            Log.W("upnp", Status);
        }
    }

    private void Set(string s) { Status = s; StatusChanged?.Invoke(); }
}
