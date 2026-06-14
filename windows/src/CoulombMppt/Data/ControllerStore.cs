using System.IO;
using System.Text.Json;

namespace CoulombMppt.Data;

// Persistent list of paired MPPT controllers + a "current" selection. Single-
// current-controller model (the MpptClient connects to one at a time), but the
// list lets the dashboard show every paired unit and lets the user switch.
// Mirrors the heater client's BoundDeviceStore. JSON blob in %APPDATA%.
public sealed class ControllerStore
{
    private static readonly JsonSerializerOptions s_jsonOpts = new() { WriteIndented = true };

    private readonly string _path;
    private readonly object _lock = new();
    private State _state = new();

    private sealed class State
    {
        public List<MpptController> Controllers { get; set; } = new();
        public string? CurrentMac { get; set; }
    }

    public ControllerStore()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "CoulombMppt");
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "controllers.json");
        Load();
    }

    public event Action? Changed;

    public IReadOnlyList<MpptController> All
    {
        get { lock (_lock) return _state.Controllers.ToArray(); }
    }

    public string? CurrentMac
    {
        get { lock (_lock) return _state.CurrentMac; }
    }

    public MpptController? Current
    {
        get
        {
            lock (_lock)
            {
                var mac = _state.CurrentMac;
                if (mac == null) return null;
                return _state.Controllers.FirstOrDefault(c =>
                    c.Mac.Equals(mac, StringComparison.OrdinalIgnoreCase));
            }
        }
    }

    public MpptController? Find(string mac)
    {
        lock (_lock) return _state.Controllers.FirstOrDefault(c =>
            c.Mac.Equals(mac, StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>Pair (or update) a controller and make it current. Re-pairing
    /// re-applies the device type / Victron key so a mistyped key can be fixed.</summary>
    public MpptController Pair(
        string mac, string? displayName,
        DeviceType deviceType = DeviceType.GenericModbusNus, string? victronKey = null)
    {
        MpptController result;
        lock (_lock)
        {
            var existing = _state.Controllers.FirstOrDefault(c =>
                c.Mac.Equals(mac, StringComparison.OrdinalIgnoreCase));
            if (existing != null)
            {
                result = existing with
                {
                    DisplayName = displayName ?? existing.DisplayName,
                    DeviceType = deviceType,
                    VictronKey = victronKey ?? existing.VictronKey,
                };
                _state.Controllers = _state.Controllers
                    .Select(c => c.Mac.Equals(mac, StringComparison.OrdinalIgnoreCase) ? result : c)
                    .ToList();
            }
            else
            {
                result = new MpptController(
                    Mac: mac,
                    DisplayName: displayName,
                    PairedAtMs: DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    DeviceType: deviceType,
                    VictronKey: victronKey);
                _state.Controllers.Add(result);
            }
            _state.CurrentMac = mac;
            Save();
        }
        Changed?.Invoke();
        return result;
    }

    public void SetCurrent(string mac)
    {
        lock (_lock) { _state.CurrentMac = mac; Save(); }
        Changed?.Invoke();
    }

    public void Remove(string mac)
    {
        lock (_lock)
        {
            _state.Controllers.RemoveAll(c => c.Mac.Equals(mac, StringComparison.OrdinalIgnoreCase));
            if (string.Equals(_state.CurrentMac, mac, StringComparison.OrdinalIgnoreCase))
                _state.CurrentMac = _state.Controllers.FirstOrDefault()?.Mac;
            Save();
        }
        Changed?.Invoke();
    }

    public void Rename(string mac, string name)
        => Mutate(mac, c => c with { DisplayName = name });

    public void UpdateProfile(string mac, string? siteLabel = null, string? iconKey = null, uint? accentArgb = null)
        => Mutate(mac, c => c with
        {
            SiteLabel  = siteLabel ?? c.SiteLabel,
            IconKey    = iconKey   ?? c.IconKey,
            AccentArgb = accentArgb ?? c.AccentArgb,
        });

    /// <summary>Cache the controller's own read-back setpoints (calibration anchors).</summary>
    public void SaveBatteryProfile(string mac, double fullV, double recoverV, double emptyV)
        => Mutate(mac, c => c with
        {
            CachedFullV = fullV, CachedRecoverV = recoverV, CachedEmptyV = emptyV,
        });

    /// <summary>Persist the user-supplied pack spec. Null args leave fields untouched.</summary>
    public void UpdateBatteryPack(string mac,
        BatteryChemistry? chemistry = null, double? nominalV = null,
        double? userFullV = null, double? userEmptyV = null,
        double? capacityKwh = null, double? capacityAh = null)
        => Mutate(mac, c => c with
        {
            PackChemistry   = chemistry   ?? c.PackChemistry,
            PackNominalV    = nominalV    ?? c.PackNominalV,
            PackUserFullV   = userFullV   ?? c.PackUserFullV,
            PackUserEmptyV  = userEmptyV  ?? c.PackUserEmptyV,
            PackCapacityKwh = capacityKwh ?? c.PackCapacityKwh,
            PackCapacityAh  = capacityAh  ?? c.PackCapacityAh,
        });

    /// <summary>Set only the fields the dashboard needs: voltage range and Ah capacity.</summary>
    public void UpdateEnergyProfile(string mac, double? nominalV, double? fullV, double? emptyV, double? capacityAh)
        => Mutate(mac, c => c with
        {
            PackNominalV   = nominalV   ?? c.PackNominalV,
            PackUserFullV  = fullV      ?? c.PackUserFullV,
            PackUserEmptyV = emptyV     ?? c.PackUserEmptyV,
            PackCapacityAh = capacityAh ?? c.PackCapacityAh,
        });

    private void Mutate(string mac, Func<MpptController, MpptController> fn)
    {
        lock (_lock)
        {
            _state.Controllers = _state.Controllers
                .Select(c => c.Mac.Equals(mac, StringComparison.OrdinalIgnoreCase) ? fn(c) : c)
                .ToList();
            Save();
        }
        Changed?.Invoke();
    }

    private void Load()
    {
        if (!File.Exists(_path)) return;
        try
        {
            var s = JsonSerializer.Deserialize<State>(File.ReadAllText(_path));
            if (s != null) _state = s;
        }
        catch { }
    }

    private void Save()
    {
        try
        {
            File.WriteAllText(_path,
                JsonSerializer.Serialize(_state, s_jsonOpts));
        }
        catch { }
    }
}
