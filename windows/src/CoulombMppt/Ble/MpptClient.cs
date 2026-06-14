using System.Diagnostics;
using System.Globalization;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;
using CoulombMppt.Data;
using CoulombMppt.Services;

namespace CoulombMppt.Ble;

// Single source of truth for everything BLE on the Windows side. Combines the
// responsibilities of the Android NusTransport (link + reassembly) and
// BleMpptSource (polling loop + decode). Mirrors the heater client's
// HeaterClient but speaks Modbus-RTU-over-Nordic-UART instead of HeatGenie.
//
// Threading: every public event fires on whatever thread the work happened on
// (the BLE callback thread or a poll-loop task). Subscribers (ViewModels) are
// expected to marshal to the UI thread via Dispatcher.BeginInvoke, exactly as
// in the heater client.
//
// Modbus is half-duplex — only one transaction may be in flight at a time. We
// enforce that with a single _ioGate semaphore, so the reassembly buffer never
// has to disambiguate two overlapping responses; whatever comes back belongs to
// the transaction currently holding the gate.
public sealed class MpptClient : IAsyncDisposable
{
    // --- Tunables (ported from BleMpptSource) -------------------------

    private const int  ConnectSettleMs      = 1500;   // wait after link-up before GATT discovery
    private const int  NotifyPauseMs        = 100;    // pause before enabling notifications
    private const int  PreferredMtu         = 64;     // best-effort; WinRT auto-negotiates
    private const int  MinUsablePduSize     = 28;     // live reply is 25B; one notification needs ATT_MTU ≥ 28
    private const int  MtuWaitMs            = 4000;   // give Windows time to negotiate the MTU up after discovery
    private const int  MtuPollMs            = 200;    // cadence while waiting for the MTU to settle
    private const int  PollIntervalMs       = 1000;   // ~1 Hz live telemetry
    private const int  PollTimeoutMs        = 2500;   // per-transaction response timeout
    private const int  SettingsTimeoutMs    = 2500;
    private const int  WriteTimeoutMs       = 2500;
    private const int  MaxTimeouts          = 4;      // consecutive live timeouts → reconnect
    private const int  SettingsRefreshEvery = 30;     // re-read settings every N live polls
    private const int  ReconnectBaseMs      = 1000;   // backoff start
    private const int  ReconnectMaxMs       = 30_000; // backoff ceiling
    private const int  CooldownMs           = 5_000;  // brief settle after a mid-session drop

    // --- Public state -------------------------------------------------

    public ConnectionState State
    {
        get => _state;
        private set { if (_state != value) { _state = value; StateChanged?.Invoke(value); } }
    }
    public event Action<ConnectionState>? StateChanged;

    public MpptLive? Live
    {
        get => _live;
        private set { _live = value; if (value != null) LiveChanged?.Invoke(value); }
    }
    public event Action<MpptLive>? LiveChanged;

    public MpptSettings? Settings
    {
        get => _settings;
        private set { _settings = value; if (value != null) SettingsChanged?.Invoke(value); }
    }
    public event Action<MpptSettings>? SettingsChanged;

    public IReadOnlyList<DiscoveredDevice> Devices
    {
        get { lock (_devices) return _devices.Values.ToArray(); }
    }
    public event Action? DevicesChanged;

    public bool Scanning => _watcher != null;
    public event Action<bool>? ScanningChanged;

    /// <summary>Raised for every raw frame on the wire (Tx and Rx) for the Diagnostics page.</summary>
    public event Action<RawFrame>? FrameSeen;

    public string LastError
    {
        get => _lastError;
        private set { if (_lastError != value) { _lastError = value; LastErrorChanged?.Invoke(value); } }
    }
    public event Action<string>? LastErrorChanged;

    /// <summary>MAC of the controller this client is connected to (or null).</summary>
    public string? CurrentMac => _currentMac;

    /// <summary>True when the demo generator is feeding fake telemetry.</summary>
    public bool DemoMode => _demoCts != null;

    /// <summary>True while frames are arriving from the Android hybrid relay.
    /// Cleared automatically if no push arrives for 30 seconds.</summary>
    public bool RelayActive => Environment.TickCount64 - _lastRelayTick < 30_000 && _lastRelayTick > 0;
    private long _lastRelayTick;

    /// <summary>When true, a mid-session drop or repeated timeouts trigger an automatic reconnect.</summary>
    public bool AutoReconnect { get; set; } = true;

    // --- Internals ----------------------------------------------------

    private ConnectionState _state = ConnectionState.Idle;
    private MpptLive?    _live;
    private MpptSettings? _settings;
    private string      _lastError = "";
    private readonly Dictionary<string, DiscoveredDevice> _devices = new(StringComparer.OrdinalIgnoreCase);

    private BluetoothLEAdvertisementWatcher? _watcher;

    private BluetoothLEDevice?  _device;
    private GattSession?        _session;
    private GattCharacteristic? _writeChar;
    private GattCharacteristic? _notifyChar;
    private string?             _currentMac;
    private string?             _desiredMac;          // what we want to stay connected to
    private CancellationTokenSource? _sessionCts;
    private Task?               _pollTask;
    private int                _reconnecting;       // 0/1, guarded via Interlocked
    private long               _cooldownUntilTicks;
    // Serialises connect attempts so a user-initiated ConnectAsync and an
    // auto-reconnect (or two reconnects) can't run ConnectInnerAsync
    // concurrently and corrupt _device/_session/_currentMac.
    private readonly SemaphoreSlim _connectGate = new(1, 1);

    // Victron Instant Readout passive watch — no GATT connection; we decode the
    // device's manufacturer advertisement as it broadcasts.
    private BluetoothLEAdvertisementWatcher? _victronWatcher;
    private string? _victronKey;
    private long _lastVictronTick;
    private CancellationTokenSource? _victronCts;

    // Reassembly buffer + the single in-flight transaction.
    private readonly List<byte> _rxBuffer = new();
    private readonly object _rxLock = new();
    private readonly SemaphoreSlim _ioGate = new(1, 1);
    // Guards the _pending/_pendingKind handoff between the poll thread (which
    // sets them in TransactAsync) and the BLE notification thread (which reads
    // them in Complete). Neither field is volatile, so the lock is also what
    // makes the write visible to the notification thread.
    private readonly object _pendingLock = new();
    private TaskCompletionSource<bool>? _pending;
    private Expect _pendingKind;
    private int _notifyCount;   // diagnostic: notifications received this session

    private enum Expect { Live, Settings, WriteAck }

    // ==================================================================
    //  Scanning
    // ==================================================================

    public void StartScan()
    {
        if (_watcher != null) return;
        lock (_devices) _devices.Clear();
        DevicesChanged?.Invoke();
        var w = new BluetoothLEAdvertisementWatcher
        {
            ScanningMode = BluetoothLEScanningMode.Active,
        };
        w.Received += OnAdvert;
        w.Stopped  += (_, e) =>
        {
            _watcher = null;
            ScanningChanged?.Invoke(false);
            if (e.Error != BluetoothError.Success)
                Log.W("ble", $"Scan stopped with {e.Error}");
        };
        _watcher = w;
        try
        {
            w.Start();
            Log.I("ble", "Scan started");
            ScanningChanged?.Invoke(true);
        }
        catch (Exception ex) { Fail($"Scan start failed: {ex.Message}"); }
    }

    public void StopScan()
    {
        var w = _watcher;
        if (w == null) return;
        try { w.Stop(); } catch (Exception ex) { Log.W("ble", $"Scan stop ignored: {ex.Message}"); }
        _watcher = null;
        ScanningChanged?.Invoke(false);
    }

    private void OnAdvert(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs e)
    {
        string  mac  = FormatMac(e.BluetoothAddress);
        string? name = string.IsNullOrEmpty(e.Advertisement.LocalName) ? null : e.Advertisement.LocalName;
        int     rssi = e.RawSignalStrengthInDBm;
        long    now  = Now();
        var     victron = ReadVictron(e.Advertisement);
        bool    isMppt = LooksLikeMppt(name, e.Advertisement.ServiceUuids) || victron != null;

        lock (_devices)
        {
            if (_devices.TryGetValue(mac, out var existing))
            {
                _devices[mac] = existing with
                {
                    Rssi         = rssi,
                    Name         = name ?? existing.Name,
                    IsKnownMppt  = existing.IsKnownMppt || isMppt,
                    LastSeenAtMs = now,
                    VictronData  = victron ?? existing.VictronData,
                };
            }
            else
            {
                _devices[mac] = new DiscoveredDevice(mac, name, rssi, isMppt, now, victron);
            }
        }
        DevicesChanged?.Invoke();
    }

    // Best-effort guess. The NUS service often isn't advertised until after
    // GATT discovery, so a false here is not a definitive "not an MPPT".
    private static bool LooksLikeMppt(string? name, IList<Guid> serviceUuids)
    {
        if (serviceUuids.Contains(BleConstants.NusService)) return true;
        if (name == null) return false;
        return name.Contains("MPPT",  StringComparison.OrdinalIgnoreCase)
            || name.Contains("SOLAR", StringComparison.OrdinalIgnoreCase)
            || name.Contains("PV",    StringComparison.OrdinalIgnoreCase);
    }

    // ==================================================================
    //  Connect / disconnect
    // ==================================================================

    public async Task ConnectAsync(string mac)
    {
        StopDemo();
        _desiredMac = mac;
        // Victron devices are read passively from their advertisement — there's
        // no GATT link to open. Route by the paired controller's device type.
        var ctrl = ServiceLocator.Controllers.Find(mac);
        if (ctrl?.DeviceType == DeviceType.VictronInstantReadout)
        {
            try { await StartVictronAsync(mac, ctrl.VictronKey).ConfigureAwait(false); }
            catch (Exception ex) { Fail($"Victron start threw: {ex.GetType().Name}: {ex.Message}"); }
            return;
        }
        // Outer guard — the WinRT bindings throw assorted COMExceptions when
        // the radio is toggling; we'd rather show a red line than crash.
        try { await ConnectInnerAsync(mac).ConfigureAwait(false); }
        catch (Exception ex) { Fail($"Connect threw: {ex.GetType().Name}: {ex.Message}"); }
    }

    public async Task DisconnectAsync()
    {
        _desiredMac = null;            // stop any reconnect loop
        StopDemo();
        await TearDownAsync().ConfigureAwait(false);
    }

    /// <summary>
    /// Inject a live frame received from the Android hybrid relay. Fires
    /// LiveChanged so every ViewModel updates exactly as if the frame arrived
    /// from BLE. Called by POST /api/v1/live/push.
    /// </summary>
    public void InjectFromRelay(MpptLive frame)
    {
        _lastRelayTick = Environment.TickCount64;
        Live = frame;
        Log.I("relay", $"rx vBat={frame.BatteryVoltage:0.0}V iCh={frame.ChargeCurrent:0.0}A soc={frame.SocEstimate:0}%");
    }

    // ==================================================================
    //  Victron Instant Readout (passive advertisement decode)
    // ==================================================================

    private async Task StartVictronAsync(string mac, string? key)
    {
        await TearDownAsync().ConfigureAwait(false);
        if (!VictronDecoder.IsValidKey(key))
        {
            Fail("This Victron device has no/invalid Instant Readout key. Re-pair and paste " +
                 "the key from VictronConnect (Settings → Product info → Instant readout).");
            return;
        }
        ulong addr;
        try { addr = MacToAddress(mac); }
        catch (Exception ex) { Fail($"Bad MAC '{mac}': {ex.Message}"); return; }

        _currentMac = mac;
        _victronKey = key;
        _lastVictronTick = 0;
        ClearError();
        State = ConnectionState.Connecting;

        var w = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Active };
        w.Received += (_, e) => OnVictronAdvert(addr, e);
        _victronWatcher = w;
        try { w.Start(); Log.I("ble", $"Victron Instant Readout watch started for {mac}"); }
        catch (Exception ex) { Fail($"Victron watcher start failed: {ex.Message}"); return; }

        _victronCts = new CancellationTokenSource();
        _ = VictronWatchdog(_victronCts.Token);
    }

    private void OnVictronAdvert(ulong addr, BluetoothLEAdvertisementReceivedEventArgs e)
    {
        if (e.BluetoothAddress != addr) return;
        var data = ReadVictron(e.Advertisement);
        if (data == null) return;
        FrameSeen?.Invoke(new RawFrame(Tx: false, Bytes: data, TimestampMs: Now()));
        var r = VictronDecoder.DecodeSolar(data, _victronKey);
        if (r == null) return;
        _lastVictronTick = Environment.TickCount64;
        State = ConnectionState.Ready;
        Live = MapVictron(r);
    }

    private async Task VictronWatchdog(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try { await Task.Delay(5_000, ct).ConfigureAwait(false); } catch { break; }
            // Adverts stop when the device goes out of range or VictronConnect
            // grabs it (Instant Readout is suppressed while connected). Keep the
            // watcher running — just reflect the gap in the status line.
            if (_lastVictronTick != 0 && Environment.TickCount64 - _lastVictronTick > 30_000
                && State == ConnectionState.Ready)
                State = ConnectionState.Reconnecting;
        }
    }

    private MpptLive MapVictron(VictronDecoder.SolarReading r)
    {
        double vBat   = r.BatteryVoltage ?? 0.0;
        double battI  = r.BatteryCurrent ?? 0.0;
        double charge = Math.Max(0, battI);
        double load   = r.LoadCurrent ?? 0.0;
        var state = MapVictronState(r.DeviceState, r.ChargerError, charge);
        double soc = _settings?.ComputeSoc(vBat) ?? MpptLive.EstimateSoc(vBat);
        return new MpptLive(
            TimestampMs:        Now(),
            BatteryVoltage:     vBat,
            ChargeCurrent:      charge,
            DischargeCurrent:   load,
            TemperatureC:       0.0,               // not in the solar record
            SolarStatusRaw:     r.DeviceState,
            WorkStatusRaw:      r.ChargerError,
            PowerStatusRaw:     0,
            TotalAccumulatedAh: 0.0,
            ChargerState:       state,
            SocEstimate:        soc);
    }

    // Victron device-state codes → the app's heuristic ChargerState enum.
    private static ChargerState MapVictronState(int state, int error, double charge) =>
        error != 0 ? ChargerState.Fault : state switch
        {
            0      => ChargerState.Idle,             // Off
            3      => ChargerState.Bulk,
            4 or 7 => ChargerState.Boost,            // Absorption / Equalize
            5 or 6 => ChargerState.Floating,         // Float / Storage
            _      => charge > 0.1 ? ChargerState.Bulk : ChargerState.Idle,
        };

    private static byte[]? ReadVictron(BluetoothLEAdvertisement adv)
    {
        foreach (var md in adv.ManufacturerData)
            if (md.CompanyId == VictronDecoder.CompanyId) return ReadBuffer(md.Data);
        return null;
    }

    private static byte[] ReadBuffer(Windows.Storage.Streams.IBuffer buf)
    {
        var b = new byte[buf.Length];
        Windows.Storage.Streams.DataReader.FromBuffer(buf).ReadBytes(b);
        return b;
    }

    /// <summary>
    /// If this client is currently targeting <paramref name="mac"/> — connected,
    /// connecting, or mid-reconnect — drop the link and cancel auto-reconnect.
    /// Used when the user forgets a controller so we don't leave a reconnect
    /// loop chasing a MAC that no longer exists in the store.
    /// </summary>
    public async Task DisconnectIfTargetingAsync(string mac)
    {
        if (string.Equals(_currentMac, mac, StringComparison.OrdinalIgnoreCase) ||
            string.Equals(_desiredMac, mac, StringComparison.OrdinalIgnoreCase))
        {
            Log.I("ble", $"Forgetting active target {mac} — disconnecting");
            await DisconnectAsync().ConfigureAwait(false);
        }
    }

    // Serialise the whole connect state machine. ConnectAsync (user-initiated)
    // and ReconnectLoop both reach ConnectInnerAsync; without the gate they can
    // run the discovery/teardown sequence at the same time and trample each
    // other's _device/_session. The reconnect paths fire ConnectInnerAsync only
    // as fire-and-forget continuations, so a queued attempt simply waits for
    // the in-flight one to release the gate — no deadlock.
    private async Task ConnectInnerAsync(string mac)
    {
        await _connectGate.WaitAsync().ConfigureAwait(false);
        try { await ConnectInnerLockedAsync(mac).ConfigureAwait(false); }
        finally { _connectGate.Release(); }
    }

    private async Task ConnectInnerLockedAsync(string mac)
    {
        Log.I("ble", $"ConnectAsync({mac}) requested");

        var s = State;
        bool alive = s is ConnectionState.Connecting
                       or ConnectionState.DiscoveringServices
                       or ConnectionState.Ready;
        if (alive && string.Equals(_currentMac, mac, StringComparison.OrdinalIgnoreCase))
        {
            Log.I("ble", $"Already {s} on {mac}, no-op");
            return;
        }

        long nowTicks = Environment.TickCount64;
        if (nowTicks < _cooldownUntilTicks)
        {
            var remain = (_cooldownUntilTicks - nowTicks) / 1000.0;
            Log.I("ble", $"Cooldown active — waiting {remain:F1}s before reconnect");
            await Task.Delay((int)(_cooldownUntilTicks - nowTicks)).ConfigureAwait(false);
        }

        await TearDownAsync().ConfigureAwait(false);

        // Is there a Bluetooth radio at all?
        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter == null) { Fail("No Bluetooth adapter found on this PC."); return; }
            if (!adapter.IsLowEnergySupported) { Fail("This Bluetooth adapter does not support BLE."); return; }
        }
        catch (Exception ex) { Fail($"BluetoothAdapter.GetDefaultAsync threw: {ex.Message}"); return; }

        ClearError();
        State = ConnectionState.Connecting;
        _currentMac = mac;

        ulong addr;
        try { addr = MacToAddress(mac); }
        catch (Exception ex) { Fail($"Bad MAC '{mac}': {ex.Message}"); return; }

        BluetoothLEDevice? dev;
        try { dev = await BluetoothLEDevice.FromBluetoothAddressAsync(addr); }
        catch (Exception ex) { Fail($"FromBluetoothAddressAsync threw: {ex.GetType().Name}: {ex.Message}"); return; }
        if (dev == null)
        {
            Fail($"OS returned no device for {mac}. Bluetooth disabled? Out of range? " +
                 "Try a fresh scan and pair again so Windows has a current advert cache.");
            return;
        }
        _device = dev;
        try { dev.ConnectionStatusChanged += OnConnectionStatusChanged; } catch { }
        Log.I("ble", $"Device handle obtained; ConnectionStatus={dev.ConnectionStatus}");

        // Open a GATT session and ask it to maintain the link. This nudges the
        // OS into negotiating a larger MTU (best-effort — WinRT gives no direct
        // MTU-request API like Android's requestMtu()).
        try
        {
            _session = await GattSession.FromDeviceIdAsync(dev.BluetoothDeviceId);
            _session.MaintainConnection = true;
            _session.MaxPduSizeChanged += OnMaxPduSizeChanged;
            Log.I("ble", $"GATT session opened; MaxPduSize={_session.MaxPduSize} (preferred {PreferredMtu})");
        }
        catch (Exception ex) { Log.W("ble", $"GattSession open ignored: {ex.Message}"); }

        // Connect-quirk #1: give the firmware time to settle before discovery.
        await Task.Delay(ConnectSettleMs).ConfigureAwait(false);

        State = ConnectionState.DiscoveringServices;

        GattDeviceServicesResult svcResult;
        try { svcResult = await dev.GetGattServicesAsync(BluetoothCacheMode.Uncached); }
        catch (Exception ex) { Fail($"GetGattServicesAsync threw: {ex.GetType().Name}: {ex.Message}"); return; }
        Log.I("ble", $"GetGattServicesAsync → Status={svcResult.Status} " +
                     $"ProtocolError={svcResult.ProtocolError} Services={svcResult.Services.Count}");
        if (svcResult.Status != GattCommunicationStatus.Success)
        {
            Fail(DescribeServiceFailure(svcResult.Status, svcResult.ProtocolError));
            return;
        }

        // Prefer the advertised NUS service; otherwise fall back to the first
        // service that has both a write and a notify characteristic.
        GattDeviceService? svc = null;
        foreach (var s2 in svcResult.Services)
        {
            Log.I("ble", $"  service {s2.Uuid}");
            if (s2.Uuid == BleConstants.NusService) { svc = s2; break; }
        }
        if (svc == null)
        {
            foreach (var s2 in svcResult.Services)
            {
                GattCharacteristicsResult cr;
                try { cr = await s2.GetCharacteristicsAsync(BluetoothCacheMode.Uncached); }
                catch { continue; }
                if (cr.Status != GattCommunicationStatus.Success) continue;
                bool hasW = cr.Characteristics.Any(c =>
                    (c.CharacteristicProperties & (GattCharacteristicProperties.Write |
                                                   GattCharacteristicProperties.WriteWithoutResponse)) != 0);
                bool hasN = cr.Characteristics.Any(c =>
                    (c.CharacteristicProperties & (GattCharacteristicProperties.Notify |
                                                   GattCharacteristicProperties.Indicate)) != 0);
                if (hasW && hasN) { svc = s2; break; }
            }
        }
        if (svc == null)
        {
            Fail($"No usable GATT service on {mac}. Services seen: " +
                 string.Join(", ", svcResult.Services.Select(x => x.Uuid)));
            return;
        }
        Log.I("ble", $"Picked service {svc.Uuid}");

        GattCharacteristicsResult charsRes;
        try { charsRes = await svc.GetCharacteristicsAsync(BluetoothCacheMode.Uncached); }
        catch (Exception ex) { Fail($"GetCharacteristicsAsync threw: {ex.GetType().Name}: {ex.Message}"); return; }
        if (charsRes.Status != GattCommunicationStatus.Success)
        {
            Fail($"GetCharacteristicsAsync failed: {charsRes.Status}");
            return;
        }

        // Pick by actual GATT PROPERTY first, then fall back to the conventional
        // NUS UUID. Property must win: this vendor's firmware ships the NUS
        // characteristics with their roles swapped relative to the Nordic
        // convention — 6e400002 carries Notify and 6e400003 carries
        // WriteWithoutResponse — so trusting the UUID labels picks both the
        // wrong way round and the CCCD enable then throws COMException on a
        // characteristic that has no notify descriptor.
        GattCharacteristic? byUuidWrite  = null, byUuidNotify  = null;
        GattCharacteristic? byPropWrite  = null, byPropNotify  = null;
        foreach (var c in charsRes.Characteristics)
        {
            var p = c.CharacteristicProperties;
            Log.I("ble", $"  char {c.Uuid}  props={p}");
            if (c.Uuid == BleConstants.NusCharRx) byUuidWrite  = c;
            if (c.Uuid == BleConstants.NusCharTx) byUuidNotify = c;
            if (byPropWrite == null && (p & (GattCharacteristicProperties.Write |
                                             GattCharacteristicProperties.WriteWithoutResponse)) != 0)
                byPropWrite = c;
            if (byPropNotify == null && (p & (GattCharacteristicProperties.Notify |
                                              GattCharacteristicProperties.Indicate)) != 0)
                byPropNotify = c;
        }
        _writeChar  = byPropWrite  ?? byUuidWrite;
        _notifyChar = byPropNotify ?? byUuidNotify;

        if (_writeChar == null) { Fail("No writeable characteristic on the selected service"); return; }
        if (_notifyChar == null) { Fail("No notify characteristic on the selected service"); return; }
        Log.I("ble", $"Write={_writeChar.Uuid}  Notify={_notifyChar.Uuid}");

        // Connect-quirk #2: brief pause before enabling notifications.
        await Task.Delay(NotifyPauseMs).ConfigureAwait(false);

        try { _notifyChar.ValueChanged += OnNotification; } catch { }
        GattCommunicationStatus enableStatus;
        try
        {
            enableStatus = await _notifyChar.WriteClientCharacteristicConfigurationDescriptorAsync(
                GattClientCharacteristicConfigurationDescriptorValue.Notify);
        }
        catch (Exception ex) { Fail($"CCCD enable threw: {ex.GetType().Name}: {ex.Message}"); return; }
        if (enableStatus != GattCommunicationStatus.Success)
        {
            Fail($"CCCD enable failed: {enableStatus}");
            return;
        }
        Log.I("ble", "Notifications enabled");

        // Wait for a usable ATT MTU before we start polling. Windows has no
        // requestMtu() — it negotiates the MTU on its own after the first ATT
        // request (the discovery above) — and MaxPduSize is read as 23 at
        // session-open before that settles. At MaxPduSize=23 a notification
        // carries only 20 bytes, so the controller's 25-byte live response is
        // truncated and silently dropped: it will NOT split a response across
        // notifications (the Android client must call requestMtu() for exactly
        // this reason). Polling at 23 yields zero frames and a reconnect
        // spiral, so give the MTU time to come up and, if it never does, drop
        // the link — a fresh connection often renegotiates to the full size.
        int pdu = await WaitForUsableMtuAsync(MtuWaitMs).ConfigureAwait(false);
        if (pdu < MinUsablePduSize)
        {
            Log.W("ble", $"MTU stuck at {pdu} (need ≥{MinUsablePduSize}); the controller's reply " +
                         "won't fit in one notification and would be dropped — reconnecting to renegotiate");
            await TearDownAsync().ConfigureAwait(false);
            if (_reconnecting == 0 && AutoReconnect &&
                string.Equals(_desiredMac, mac, StringComparison.OrdinalIgnoreCase))
                _ = Task.Run(() => ReconnectLoop(mac));
            return;
        }

        _notifyCount = 0;
        lock (_rxLock) _rxBuffer.Clear();
        State = ConnectionState.Ready;
        ClearError();
        _sessionCts = new CancellationTokenSource();
        Log.I("ble", "Link is Ready — starting poll loop");
        _pollTask = Task.Run(() => PollLoop(_sessionCts.Token));
    }

    // WinRT exposes no requestMtu(); the OS negotiates the ATT MTU on its own
    // shortly after the first GATT request, raising MaxPduSizeChanged when it
    // lands. All we can do is poll MaxPduSize until it reaches a size that fits
    // the controller's reply, or give up.
    private async Task<int> WaitForUsableMtuAsync(int budgetMs)
    {
        var sw = Stopwatch.StartNew();
        int pdu = _session?.MaxPduSize ?? 0;
        while (pdu < MinUsablePduSize && sw.ElapsedMilliseconds < budgetMs)
        {
            await Task.Delay(MtuPollMs).ConfigureAwait(false);
            pdu = _session?.MaxPduSize ?? 0;
        }
        Log.I("ble", $"MTU settled at MaxPduSize={pdu} after {sw.ElapsedMilliseconds}ms");
        return pdu;
    }

    private void OnMaxPduSizeChanged(GattSession sender, object args)
        => Log.I("ble", $"MaxPduSize changed → {sender.MaxPduSize}");

    private static string DescribeServiceFailure(GattCommunicationStatus s, byte? protoErr) => s switch
    {
        GattCommunicationStatus.Unreachable
            => "Controller is not reachable. It may be out of range, asleep, or already connected to another device. " +
               "Power-cycle the controller, then try again.",
        GattCommunicationStatus.AccessDenied
            => "Windows refused the GATT access — the controller may need to be paired through " +
               "Windows Settings → Bluetooth & devices first. Open Settings, add it, then retry.",
        GattCommunicationStatus.ProtocolError
            => $"GATT protocol error 0x{protoErr:X2}. The controller rejected our handshake.",
        _ => $"GetGattServicesAsync failed with {s}",
    };

    private async Task TearDownAsync()
    {
        // Stop any Victron advertisement watch (independent of the GATT path).
        try { _victronCts?.Cancel(); } catch { }
        _victronCts = null;
        var vw = _victronWatcher;
        _victronWatcher = null;
        if (vw != null) { try { vw.Stop(); } catch { } }
        _victronKey = null;

        // Unblock a transaction parked on the dead link so it releases _ioGate
        // promptly, then take the gate ourselves before disposing anything.
        // Holding the gate guarantees we never dispose the GATT characteristics
        // out from under an in-flight WriteValueAsync — which previously
        // surfaced as COM/ObjectDisposed exceptions and a stuck transaction.
        lock (_pendingLock) { _pending?.TrySetResult(false); }
        await _ioGate.WaitAsync().ConfigureAwait(false);
        try
        {
            try { _sessionCts?.Cancel(); } catch { }
            _pollTask = null;
            _sessionCts?.Dispose();
            _sessionCts = null;

            var notify = _notifyChar;
            if (notify != null)
            {
                try
                {
                    await notify.WriteClientCharacteristicConfigurationDescriptorAsync(
                        GattClientCharacteristicConfigurationDescriptorValue.None);
                }
                catch { /* link may already be down */ }
                notify.ValueChanged -= OnNotification;
            }
            _notifyChar = null;
            _writeChar  = null;

            if (_session != null)
            {
                try { _session.MaxPduSizeChanged -= OnMaxPduSizeChanged; } catch { }
                try { _session.MaintainConnection = false; _session.Dispose(); } catch { }
                _session = null;
            }

            if (_device != null)
            {
                _device.ConnectionStatusChanged -= OnConnectionStatusChanged;
                _device.Dispose();
                _device = null;
            }

            lock (_pendingLock) { _pending = null; }
            lock (_rxLock) _rxBuffer.Clear();

            _currentMac = null;
            if (State != ConnectionState.Failed) State = ConnectionState.Idle;
        }
        finally { _ioGate.Release(); }
    }

    private void OnConnectionStatusChanged(BluetoothLEDevice sender, object args)
    {
        Log.I("ble", $"ConnectionStatusChanged → {sender.ConnectionStatus}");
        if (sender.ConnectionStatus != BluetoothConnectionStatus.Disconnected) return;

        bool wasReady = State == ConnectionState.Ready;
        if (wasReady)
        {
            _cooldownUntilTicks = Environment.TickCount64 + CooldownMs;
            Log.I("ble", $"Mid-session drop — applying {CooldownMs}ms cooldown");
        }
        var mac = _desiredMac;
        _ = Task.Run(async () =>
        {
            await TearDownAsync().ConfigureAwait(false);
            if (wasReady && AutoReconnect && mac != null)
                await ReconnectLoop(mac).ConfigureAwait(false);
        });
    }

    // ==================================================================
    //  Reconnect
    // ==================================================================

    private async Task ForceReconnect()
    {
        var mac = _desiredMac;
        await TearDownAsync().ConfigureAwait(false);
        if (mac != null && AutoReconnect) await ReconnectLoop(mac).ConfigureAwait(false);
    }

    private async Task ReconnectLoop(string mac)
    {
        // Atomic guard: OnConnectionStatusChanged and the poll-loop timeout path
        // can both kick off a reconnect at the same time. A plain bool check
        // lets them both slip through and run two loops fighting over
        // _desiredMac; CompareExchange admits exactly one.
        if (Interlocked.CompareExchange(ref _reconnecting, 1, 0) != 0) return;
        int backoff = ReconnectBaseMs;
        try
        {
            while (AutoReconnect &&
                   string.Equals(_desiredMac, mac, StringComparison.OrdinalIgnoreCase))
            {
                State = ConnectionState.Reconnecting;
                Log.I("ble", $"Reconnecting to {mac} in {backoff}ms");
                try { await Task.Delay(backoff).ConfigureAwait(false); } catch { }
                if (!AutoReconnect ||
                    !string.Equals(_desiredMac, mac, StringComparison.OrdinalIgnoreCase)) break;
                try { await ConnectInnerAsync(mac).ConfigureAwait(false); }
                catch (Exception ex) { Log.W("ble", $"Reconnect attempt threw: {ex.Message}"); }
                if (State == ConnectionState.Ready) break;
                backoff = Math.Min(backoff * 2, ReconnectMaxMs);
            }
        }
        finally { Interlocked.Exchange(ref _reconnecting, 0); }
    }

    // ==================================================================
    //  Polling loop
    // ==================================================================

    private async Task PollLoop(CancellationToken ct)
    {
        int consecutiveTimeouts = 0;
        int sinceSettings = SettingsRefreshEvery;   // force a settings read on the first tick

        while (!ct.IsCancellationRequested && State == ConnectionState.Ready)
        {
            if (sinceSettings >= SettingsRefreshEvery)
            {
                sinceSettings = 0;
                try { await ReadSettingsInternalAsync(ct).ConfigureAwait(false); }
                catch (OperationCanceledException) { break; }
                catch (Exception ex) { Log.W("ble", $"Settings read failed: {ex.Message}"); }
            }

            bool ok;
            try { ok = await TransactAsync(MpptProtocol.PollLive(), Expect.Live, PollTimeoutMs, ct).ConfigureAwait(false); }
            catch (OperationCanceledException) { break; }

            if (ok)
            {
                consecutiveTimeouts = 0;
            }
            else
            {
                consecutiveTimeouts++;
                Log.W("ble", $"Live poll timeout ({consecutiveTimeouts}/{MaxTimeouts})");
                if (consecutiveTimeouts >= MaxTimeouts)
                {
                    Log.W("ble", "Too many timeouts — forcing reconnect");
                    _ = ForceReconnect();
                    break;
                }
            }

            sinceSettings++;
            try { await Task.Delay(PollIntervalMs, ct).ConfigureAwait(false); }
            catch (OperationCanceledException) { break; }
        }
    }

    // ==================================================================
    //  Modbus transactions
    // ==================================================================

    /// <summary>Re-read the controller's settings registers. No-op unless Ready.</summary>
    public Task<bool> ReadSettingsAsync()
        => State == ConnectionState.Ready
            ? ReadSettingsInternalAsync(_sessionCts?.Token ?? CancellationToken.None)
            : Task.FromResult(false);

    private Task<bool> ReadSettingsInternalAsync(CancellationToken ct)
        => TransactAsync(MpptProtocol.PollSettings(), Expect.Settings, SettingsTimeoutMs, ct);

    /// <summary>
    /// Write a single holding register, then re-read settings so the cached
    /// snapshot reflects the change. Caller handles unit conversion.
    /// </summary>
    public async Task<bool> WriteRegisterAsync(int address, int value)
    {
        if (State != ConnectionState.Ready) return false;
        var ct = _sessionCts?.Token ?? CancellationToken.None;
        bool ok = await TransactAsync(MpptProtocol.WriteRegister(address, value),
                                      Expect.WriteAck, WriteTimeoutMs, ct).ConfigureAwait(false);
        Log.I("ble", $"WriteRegister(0x{address:X4}, {value}) → {(ok ? "ACK" : "no-ack")}");
        if (ok)
        {
            try { await ReadSettingsInternalAsync(ct).ConfigureAwait(false); } catch { }
        }
        return ok;
    }

    // --- Convenience setting writers (unit conversion applied here) ----

    public Task<bool> SetChargeVoltageAsync(double v)   => WriteRegisterAsync(MpptProtocol.Reg.ChargeVoltageSetpoint,  Volts(v));
    public Task<bool> SetCutoffVoltageAsync(double v)   => WriteRegisterAsync(MpptProtocol.Reg.CutoffVoltageSetpoint,  Volts(v));
    public Task<bool> SetRecoveryVoltageAsync(double v) => WriteRegisterAsync(MpptProtocol.Reg.RecoveryVoltageSetpoint, Volts(v));
    public Task<bool> SetBatteryTypeAsync(int code)     => WriteRegisterAsync(MpptProtocol.Reg.BatteryType, code);
    public Task<bool> SetOutputModeAsync(int code)      => WriteRegisterAsync(MpptProtocol.Reg.OutputMode, code);
    public Task<bool> SetManualLoadAsync(bool on)       => WriteRegisterAsync(MpptProtocol.Reg.ManualLoadOn, on ? 1 : 0);
    public Task<bool> SetVoltageMonitorModeAsync(int c) => WriteRegisterAsync(MpptProtocol.Reg.VoltageMonitorMode, c);
    public async Task<bool> SetTimerAsync(int hours, int minutes)
    {
        bool a = await WriteRegisterAsync(MpptProtocol.Reg.TimerHour,   Math.Clamp(hours,   0, 23)).ConfigureAwait(false);
        bool b = await WriteRegisterAsync(MpptProtocol.Reg.TimerMinute, Math.Clamp(minutes, 0, 59)).ConfigureAwait(false);
        return a && b;
    }

    private static int Volts(double v) => (int)Math.Round(Math.Clamp(v, 0, 100) * 10.0);

    // The one place a Modbus frame is sent and its response awaited. Holds the
    // IO gate for the whole round-trip so the reassembly buffer only ever
    // contains the response to the transaction in flight.
    private async Task<bool> TransactAsync(byte[] frame, Expect expect, int timeoutMs, CancellationToken ct)
    {
        if (_writeChar == null) return false;
        await _ioGate.WaitAsync(ct).ConfigureAwait(false);
        var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        try
        {
            lock (_pendingLock) { _pending = tcs; _pendingKind = expect; }

            if (!await WriteRawAsync(frame).ConfigureAwait(false))
                return false;

            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeoutCts.CancelAfter(timeoutMs);
            using (timeoutCts.Token.Register(() => tcs.TrySetResult(false)))
            {
                return await tcs.Task.ConfigureAwait(false);
            }
        }
        finally
        {
            // Only clear if it's still ours — a teardown may have already
            // replaced/nulled it.
            lock (_pendingLock) { if (ReferenceEquals(_pending, tcs)) _pending = null; }
            _ioGate.Release();
        }
    }

    private async Task<bool> WriteRawAsync(byte[] bytes)
    {
        var ch = _writeChar;
        if (ch == null) return false;
        try
        {
            using var w = new DataWriter();
            w.WriteBytes(bytes);
            var opt = (ch.CharacteristicProperties & GattCharacteristicProperties.Write) != 0
                ? GattWriteOption.WriteWithResponse
                : GattWriteOption.WriteWithoutResponse;
            var status = await ch.WriteValueAsync(w.DetachBuffer(), opt);
            bool ok = status == GattCommunicationStatus.Success;
            if (!ok) Log.W("ble", $"Write failed: {status}");
            FrameSeen?.Invoke(new RawFrame(Tx: true, Bytes: bytes, TimestampMs: Now()));
            return ok;
        }
        catch (Exception ex) { Log.E("ble", $"Write threw: {ex.Message}"); return false; }
    }

    // ==================================================================
    //  Notifications + reassembly
    // ==================================================================

    private void OnNotification(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        try
        {
            var buf = args.CharacteristicValue;
            byte[] bytes = new byte[buf.Length];
            DataReader.FromBuffer(buf).ReadBytes(bytes);
            // Log the first few notifications (and every 30th thereafter) so the
            // log file shows definitively that frames are arriving and at what
            // size — the MTU=23 truncation manifests here as short/absent frames.
            _notifyCount++;
            if (_notifyCount <= 10 || _notifyCount % 30 == 0)
                Log.I("ble", $"notify rx #{_notifyCount}  {bytes.Length}B  {BitConverter.ToString(bytes)}");
            FrameSeen?.Invoke(new RawFrame(Tx: false, Bytes: bytes, TimestampMs: Now()));
            ProcessIncoming(bytes);
        }
        catch (Exception ex)
        {
            // A notification can land just as the device is being torn down;
            // reading its buffer then throws. Nothing to recover — drop it.
            Log.W("ble", $"Notification dropped: {ex.Message}");
        }
    }

    // Accumulate notification bytes and pull out whole Modbus frames. Resync
    // by dropping a single byte whenever the head doesn't parse — ported from
    // the Android NusTransport reassembler.
    private void ProcessIncoming(byte[] chunk)
    {
        var ready = new List<ModbusFrame.Response>();
        lock (_rxLock)
        {
            _rxBuffer.AddRange(chunk);
            while (true)
            {
                if (_rxBuffer.Count < 5) break;                       // need slave+fn+bc+crc minimum
                if ((_rxBuffer[0] & 0xFF) != ModbusFrame.Slave) { _rxBuffer.RemoveAt(0); continue; }

                int fn = _rxBuffer[1] & 0xFF;
                int total;
                if (fn == ModbusFrame.FnRead)       total = 3 + (_rxBuffer[2] & 0xFF) + 2;
                else if (fn == ModbusFrame.FnWrite) total = 8;
                else { _rxBuffer.RemoveAt(0); continue; }            // unknown fn → resync

                if (_rxBuffer.Count < total) break;                  // wait for the rest

                var frame = _rxBuffer.GetRange(0, total).ToArray();
                var resp  = ModbusFrame.Parse(frame);
                if (resp != null) { _rxBuffer.RemoveRange(0, total); ready.Add(resp); }
                else              { _rxBuffer.RemoveAt(0); }          // bad CRC → resync one byte
            }
        }
        foreach (var r in ready) RouteResponse(r);
    }

    private void RouteResponse(ModbusFrame.Response resp)
    {
        try
        {
            if (resp.FunctionCode == ModbusFrame.FnRead)
            {
                var regs = resp.Registers();
                // Routing: the settings read returns 9 registers (18 bytes);
                // anything else is live telemetry.
                if (resp.Payload.Length == 18)
                {
                    DecodeSettings(regs);
                    Complete(Expect.Settings);
                }
                else
                {
                    DecodeLive(regs);
                    Complete(Expect.Live);
                }
            }
            else if (resp.FunctionCode == ModbusFrame.FnWrite)
            {
                Complete(Expect.WriteAck);
            }
        }
        catch (Exception ex) { Log.W("ble", $"Decode failed: {ex.Message}"); }
    }

    private void Complete(Expect kind)
    {
        lock (_pendingLock)
        {
            var p = _pending;
            if (p != null && _pendingKind == kind) p.TrySetResult(true);
        }
    }

    // ==================================================================
    //  Decode
    // ==================================================================

    private void DecodeLive(int[] regs)
    {
        if (regs.Length < 10) { Log.W("ble", $"Live frame too short: {regs.Length} regs"); return; }

        double vBat       = regs[1] / 10.0;
        double iCharge    = regs[2] / 10.0;
        double iDischarge = regs[3] / 10.0;
        double tempC      = regs[4] / 100.0;
        int    solarRaw   = regs[5];
        int    workRaw    = regs[6];
        int    powerRaw   = regs[7];
        int    energyLo   = regs[8];
        int    energyHi   = regs[9];
        double totalAh    = (1000.0 * energyHi + energyLo) / 10.0;

        var state = ChargerStateLogic.FromRegisters(solarRaw, workRaw, powerRaw, iCharge, iDischarge, vBat);
        double soc = _settings?.ComputeSoc(vBat) ?? MpptLive.EstimateSoc(vBat);

        Live = new MpptLive(
            TimestampMs:        Now(),
            BatteryVoltage:     vBat,
            ChargeCurrent:      iCharge,
            DischargeCurrent:   iDischarge,
            TemperatureC:       tempC,
            SolarStatusRaw:     solarRaw,
            WorkStatusRaw:      workRaw,
            PowerStatusRaw:     powerRaw,
            TotalAccumulatedAh: totalAh,
            ChargerState:       state,
            SocEstimate:        soc);
    }

    private void DecodeSettings(int[] regs)
    {
        if (regs.Length < 9) { Log.W("ble", $"Settings frame too short: {regs.Length} regs"); return; }

        Settings = new MpptSettings(
            BatteryType:            regs[0],
            TimerHours:             regs[1],
            TimerMinutes:           regs[2],
            ChargeVoltageSetpoint:  regs[3] / 10.0,
            OutputMode:             regs[4],
            CutoffVoltageSetpoint:  regs[5] / 10.0,
            ManualLoadOn:           regs[6] != 0,
            VoltageMonitorMode:     regs[7],
            RecoveryVoltageSetpoint: regs[8] / 10.0);
    }

    // ==================================================================
    //  Readiness helper (used by the remote API's Fire pattern)
    // ==================================================================

    public async Task<bool> WaitReadyAsync(int timeoutMs)
    {
        if (State == ConnectionState.Ready) return true;
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < timeoutMs)
        {
            if (State == ConnectionState.Ready) return true;
            await Task.Delay(100).ConfigureAwait(false);
        }
        return State == ConnectionState.Ready;
    }

    // ==================================================================
    //  Demo mode (FakeMpptSource port)
    // ==================================================================

    private CancellationTokenSource? _demoCts;

    /// <summary>
    /// Start feeding synthetic telemetry — a 12 V lead-acid pack on a
    /// partly-cloudy day. Lets the UI be exercised with no hardware present.
    /// Ported from the Android FakeMpptSource.
    /// </summary>
    public void StartDemo()
    {
        if (_demoCts != null) return;
        _ = DisconnectRealAsync();   // drop any real link first

        _currentMac = "DE:MO:00:00:00:01";
        _desiredMac = null;
        var cts = new CancellationTokenSource();
        _demoCts = cts;

        Settings = new MpptSettings(
            BatteryType: 1, TimerHours: 0, TimerMinutes: 0,
            ChargeVoltageSetpoint: 14.4, OutputMode: 1,
            CutoffVoltageSetpoint: 11.1, ManualLoadOn: true,
            VoltageMonitorMode: 0, RecoveryVoltageSetpoint: 12.6);

        State = ConnectionState.Ready;
        Log.I("ble", "Demo mode started");

        _ = Task.Run(async () =>
        {
            double tSec = 0;
            double baseAh = 486.4;
            try
            {
                while (!cts.IsCancellationRequested)
                {
                    double sun = Math.Max(0, (Math.Sin(tSec * 2 * Math.PI / 60.0 - Math.PI / 2) + 1) / 2);
                    double pv   = sun * 5.5;
                    double load = 1.2;
                    double vBat = 12.6 + sun * 1.6 - 0.4 * (1 - sun);
                    double temp = 24 + sun * 12;
                    double totalAh = baseAh + tSec / 3600.0 * pv;

                    var state = ChargerStateLogic.FromRegisters(0, 0, 1, pv, load, vBat);
                    double soc = _settings?.ComputeSoc(vBat) ?? MpptLive.EstimateSoc(vBat);

                    Live = new MpptLive(
                        TimestampMs:        Now(),
                        BatteryVoltage:     Math.Round(vBat, 2),
                        ChargeCurrent:      Math.Round(pv, 2),
                        DischargeCurrent:   load,
                        TemperatureC:       Math.Round(temp, 1),
                        SolarStatusRaw:     0,
                        WorkStatusRaw:      0,
                        PowerStatusRaw:     1,
                        TotalAccumulatedAh: Math.Round(totalAh, 1),
                        ChargerState:       state,
                        SocEstimate:        soc);

                    tSec += 1;
                    await Task.Delay(1000, cts.Token).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) { /* stopped */ }
        });
    }

    public void StopDemo()
    {
        var cts = _demoCts;
        if (cts == null) return;
        _demoCts = null;
        try { cts.Cancel(); cts.Dispose(); } catch { }
        _currentMac = null;
        Log.I("ble", "Demo mode stopped");
        if (State == ConnectionState.Ready) State = ConnectionState.Idle;
    }

    // Tear down a real link without clearing demo state (used when entering demo).
    private async Task DisconnectRealAsync()
    {
        if (_device != null || _sessionCts != null)
            await TearDownAsync().ConfigureAwait(false);
    }

    // ==================================================================
    //  Utility
    // ==================================================================

    private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private static string FormatMac(ulong addr)
    {
        var s = addr.ToString("X12", CultureInfo.InvariantCulture);
        return string.Join(':', s[0..2], s[2..4], s[4..6], s[6..8], s[8..10], s[10..12]);
    }

    private static ulong MacToAddress(string mac)
    {
        var clean = mac.Replace(":", "").Replace("-", "").Replace(" ", "");
        return Convert.ToUInt64(clean, 16);
    }

    private void Fail(string reason)
    {
        Log.W("ble", $"FAIL: {reason}");
        // In hybrid mode the phone holds the BLE link and relays telemetry to us;
        // our own local BLE attempts are expected to fail (the controller's single
        // slot is taken / it's out of our range). Don't surface that as an error
        // or flip to Failed while relay frames are arriving.
        if (RelayActive) return;
        LastError = reason;
        State = ConnectionState.Failed;
    }

    private void ClearError()
    {
        if (_lastError.Length > 0) LastError = "";
    }

    public async ValueTask DisposeAsync()
    {
        _desiredMac = null;
        StopScan();
        StopDemo();
        await TearDownAsync().ConfigureAwait(false);
        _ioGate.Dispose();
        _connectGate.Dispose();
    }
}
