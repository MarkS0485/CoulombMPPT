using System.Windows;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using CoulombMppt.Ble;
using CoulombMppt.Data;
using CoulombMppt.Services;

namespace CoulombMppt.Ui.ViewModels;

// Drives the History page's stack of MiniCharts. Instead of re-reading the file
// for a fixed look-back window, it keeps the controller's rows cached, exposes a
// shared time viewport (ViewStartMs/ViewEndMs) that the charts pan and zoom
// together, and downsamples whatever range is visible into ~TargetBuckets points
// so even the 30-day "All" view stays smooth. CrosshairMs is shared so a hover on
// one chart lines up across all of them.
public sealed partial class HistoryViewModel : ObservableObject
{
    private readonly MpptClient      _ble   = ServiceLocator.Ble;
    private readonly ControllerStore _ctrls = ServiceLocator.Controllers;
    private readonly HistoryStore    _hist  = ServiceLocator.History;

    private const long HourMs = 3_600_000L;
    private const long DayMs  = 24 * HourMs;
    private const int  TargetBuckets = 600;

    private readonly DispatcherTimer _debounce;
    private string? _cacheMac;
    private List<LiveSample> _cache = new();
    private long _cacheTick;
    private bool _adjusting;   // suppresses reload while we set both ends at once

    public HistoryViewModel()
    {
        _debounce = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(150) };
        _debounce.Tick += (_, _) => { _debounce.Stop(); LoadViewport(); };

        _ctrls.Changed   += () => RunOnUi(ResetForCurrent);
        _ble.LiveChanged += _ => RunOnUi(OnLive);
        ResetForCurrent();
    }

    // 0 = 1h, 1 = 6h, 2 = 24h, 3 = 7d, 4 = 30d, 5 = All
    [ObservableProperty] private int _rangeIndex = 2;

    [ObservableProperty] private bool   _hasData;
    [ObservableProperty] private string _summary = "";

    // Shared viewport + crosshair (two-way bound across the chart stack).
    [ObservableProperty] private long _viewStartMs;
    [ObservableProperty] private long _viewEndMs;
    [ObservableProperty] private long _crosshairMs;
    // Travel clamps, one-way to the charts.
    [ObservableProperty] private long _minViewMs;
    [ObservableProperty] private long _maxViewMs;

    [ObservableProperty] private IReadOnlyList<double> _voltageSeries = Array.Empty<double>();
    [ObservableProperty] private IReadOnlyList<double> _pvSeries      = Array.Empty<double>();
    [ObservableProperty] private IReadOnlyList<double> _loadSeries    = Array.Empty<double>();
    [ObservableProperty] private IReadOnlyList<double> _socSeries     = Array.Empty<double>();
    [ObservableProperty] private IReadOnlyList<long>   _timeAxis      = Array.Empty<long>();

    private static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private long SpanFor(int idx) => idx switch
    {
        0 => HourMs,
        1 => 6 * HourMs,
        2 => DayMs,
        3 => 7 * DayMs,
        4 => 30 * DayMs,
        _ => Math.Max(HourMs, NowMs() - (_cache.Count > 0 ? _cache[0].TimestampMs : NowMs() - DayMs)),
    };

    partial void OnRangeIndexChanged(int value)
    {
        long now = NowMs();
        MaxViewMs = now;
        SetViewport(now - SpanFor(value), now);
    }

    partial void OnViewStartMsChanged(long value) => ScheduleLoad();
    partial void OnViewEndMsChanged(long value) => ScheduleLoad();

    private void ScheduleLoad()
    {
        if (_adjusting) return;
        _debounce.Stop();
        _debounce.Start();
    }

    private void SetViewport(long s, long e)
    {
        _adjusting = true;
        ViewStartMs = s;
        ViewEndMs = e;
        _adjusting = false;
        ScheduleLoad();
    }

    [RelayCommand]
    private void Refresh() { RefreshCache(force: true); LoadViewport(); }

    // Snap back to the live edge keeping the current span.
    [RelayCommand]
    private void Live()
    {
        long now = NowMs();
        long span = Math.Max(HourMs, ViewEndMs - ViewStartMs);
        MaxViewMs = now;
        SetViewport(now - span, now);
    }

    [RelayCommand]
    private void Clear()
    {
        var mac = _ctrls.CurrentMac;
        if (!string.IsNullOrEmpty(mac)) { _hist.Clear(mac!); ResetForCurrent(); }
    }

    private void ResetForCurrent()
    {
        _cacheMac = null;
        _cache = new();
        RefreshCache(force: true);
        long now = NowMs();
        MaxViewMs = now;
        MinViewMs = _cache.Count > 0 ? _cache[0].TimestampMs : now - 30 * DayMs;
        SetViewport(now - SpanFor(RangeIndex), now);
    }

    private void OnLive()
    {
        RefreshCache(force: false);               // throttled internally
        long now = NowMs();
        long span = Math.Max(HourMs, ViewEndMs - ViewStartMs);
        bool following = ViewEndMs >= MaxViewMs - 3_000;
        MaxViewMs = now;
        if (following) SetViewport(now - span, now);
        else ScheduleLoad();
    }

    private void RefreshCache(bool force)
    {
        var mac = _ctrls.CurrentMac;
        if (string.IsNullOrEmpty(mac)) { _cache = new(); _cacheMac = null; return; }
        long tick = Environment.TickCount64;
        if (!force && mac == _cacheMac && tick - _cacheTick < 15_000) return;
        long since = NowMs() - 30 * DayMs;
        var rows = _hist.Query(mac!, since);
        _cache = rows.OrderBy(r => r.TimestampMs).ToList();
        _cacheMac = mac;
        _cacheTick = tick;
        if (_cache.Count > 0) MinViewMs = _cache[0].TimestampMs;
    }

    private void ClearSeries()
    {
        VoltageSeries = PvSeries = LoadSeries = SocSeries = Array.Empty<double>();
        TimeAxis = Array.Empty<long>();
    }

    private void LoadViewport()
    {
        if (_cache.Count == 0)
        {
            HasData = false;
            Summary = string.IsNullOrEmpty(_ctrls.CurrentMac)
                ? "No controller selected."
                : "No samples recorded in this window yet.";
            ClearSeries();
            return;
        }

        long start = ViewStartMs, end = ViewEndMs;
        if (end <= start) return;
        long span = end - start;
        long margin = span / 4;
        long lo = start - margin, hi = end + margin;
        long bucket = Math.Max(1, span / TargetBuckets);

        var vs = new List<double>(); var pv = new List<double>();
        var ld = new List<double>(); var soc = new List<double>();
        var ta = new List<long>();

        long curKey = long.MinValue;
        int n = 0; double sV = 0, sP = 0, sL = 0, sS = 0; long sT = 0;

        void Flush()
        {
            if (n == 0) return;
            vs.Add(sV / n); pv.Add(sP / n); ld.Add(sL / n); soc.Add(sS / n); ta.Add(sT / n);
        }

        foreach (var r in _cache)
        {
            if (r.TimestampMs < lo || r.TimestampMs > hi) continue;
            long key = r.TimestampMs / bucket;
            if (key != curKey) { Flush(); curKey = key; n = 0; sV = sP = sL = sS = 0; sT = 0; }
            sV += r.BatteryVoltage; sP += r.PvWatts; sL += r.LoadWatts; sS += r.SocPercent;
            sT += r.TimestampMs; n++;
        }
        Flush();

        if (vs.Count == 0)
        {
            HasData = false;
            Summary = "No samples in this range — pan or widen it.";
            ClearSeries();
            return;
        }

        VoltageSeries = vs.ToArray();
        PvSeries      = pv.ToArray();
        LoadSeries    = ld.ToArray();
        SocSeries     = soc.ToArray();
        TimeAxis      = ta.ToArray();

        var a = DateTimeOffset.FromUnixTimeMilliseconds(start).ToLocalTime();
        var b = DateTimeOffset.FromUnixTimeMilliseconds(end).ToLocalTime();
        HasData = true;
        Summary = $"{vs.Count} pts · {a:dd MMM HH:mm} → {b:dd MMM HH:mm}";
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
