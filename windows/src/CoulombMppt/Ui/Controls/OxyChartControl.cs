using System.Collections;
using System.Globalization;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using OxyPlot;
using OxyPlot.Axes;
using OxyPlot.Series;
using OxyPlot.Wpf;

namespace CoulombMppt.Ui.Controls;

// OxyPlot-backed replacement for MiniChart.
// Two modes, determined at runtime (no IsViewportMode property — mirrors MiniChart behaviour):
//   Sparkline: ViewStartMs/ViewEndMs are both zero → index-based X, no interaction.
//   Viewport:  ViewStartMs/ViewEndMs are non-zero → DateTimeAxis, pan/zoom, shared crosshair.
public partial class OxyChartControl : System.Windows.Controls.UserControl
{
    // ------------------------------------------------------------------ //
    //  Dependency properties
    // ------------------------------------------------------------------ //

    public static readonly DependencyProperty ValuesProperty =
        DependencyProperty.Register(nameof(Values), typeof(IEnumerable), typeof(OxyChartControl),
            new PropertyMetadata(null, OnDataChanged));

    public static readonly DependencyProperty TimestampsProperty =
        DependencyProperty.Register(nameof(Timestamps), typeof(IEnumerable), typeof(OxyChartControl),
            new PropertyMetadata(null, OnDataChanged));

    public static readonly DependencyProperty LineColorProperty =
        DependencyProperty.Register(nameof(LineColor), typeof(Color), typeof(OxyChartControl),
            new PropertyMetadata(Color.FromRgb(0x0F, 0x76, 0x6E), OnDataChanged));

    public static readonly DependencyProperty LabelProperty =
        DependencyProperty.Register(nameof(Label), typeof(string), typeof(OxyChartControl),
            new PropertyMetadata("", OnLabelChanged));

    public static readonly DependencyProperty UnitProperty =
        DependencyProperty.Register(nameof(Unit), typeof(string), typeof(OxyChartControl),
            new PropertyMetadata("", OnDataChanged));

    public static readonly DependencyProperty MinForcedProperty =
        DependencyProperty.Register(nameof(MinForced), typeof(double?), typeof(OxyChartControl),
            new PropertyMetadata(null, OnDataChanged));

    public static readonly DependencyProperty MaxForcedProperty =
        DependencyProperty.Register(nameof(MaxForced), typeof(double?), typeof(OxyChartControl),
            new PropertyMetadata(null, OnDataChanged));

    // Two-way: viewport left/right edge in unix-ms.
    public static readonly DependencyProperty ViewStartMsProperty =
        DependencyProperty.Register(nameof(ViewStartMs), typeof(long), typeof(OxyChartControl),
            new FrameworkPropertyMetadata(0L,
                FrameworkPropertyMetadataOptions.BindsTwoWayByDefault,
                OnViewportChanged));

    public static readonly DependencyProperty ViewEndMsProperty =
        DependencyProperty.Register(nameof(ViewEndMs), typeof(long), typeof(OxyChartControl),
            new FrameworkPropertyMetadata(0L,
                FrameworkPropertyMetadataOptions.BindsTwoWayByDefault,
                OnViewportChanged));

    // Two-way: hover position in unix-ms; 0 = not scrubbing.
    public static readonly DependencyProperty CrosshairMsProperty =
        DependencyProperty.Register(nameof(CrosshairMs), typeof(long), typeof(OxyChartControl),
            new FrameworkPropertyMetadata(0L,
                FrameworkPropertyMetadataOptions.BindsTwoWayByDefault,
                OnCrosshairChanged));

    // Travel clamps — 0 = unbounded.
    public static readonly DependencyProperty MinViewMsProperty =
        DependencyProperty.Register(nameof(MinViewMs), typeof(long), typeof(OxyChartControl),
            new PropertyMetadata(0L));

    public static readonly DependencyProperty MaxViewMsProperty =
        DependencyProperty.Register(nameof(MaxViewMs), typeof(long), typeof(OxyChartControl),
            new PropertyMetadata(0L));

    // ------------------------------------------------------------------ //
    //  CLR accessors
    // ------------------------------------------------------------------ //

    public IEnumerable? Values     { get => (IEnumerable?)GetValue(ValuesProperty);     set => SetValue(ValuesProperty, value); }
    public IEnumerable? Timestamps { get => (IEnumerable?)GetValue(TimestampsProperty); set => SetValue(TimestampsProperty, value); }
    public Color        LineColor  { get => (Color)GetValue(LineColorProperty);          set => SetValue(LineColorProperty, value); }
    public string       Label      { get => (string)GetValue(LabelProperty);             set => SetValue(LabelProperty, value); }
    public string       Unit       { get => (string)GetValue(UnitProperty);              set => SetValue(UnitProperty, value); }
    public double?      MinForced  { get => (double?)GetValue(MinForcedProperty);        set => SetValue(MinForcedProperty, value); }
    public double?      MaxForced  { get => (double?)GetValue(MaxForcedProperty);        set => SetValue(MaxForcedProperty, value); }
    public long         ViewStartMs { get => (long)GetValue(ViewStartMsProperty);        set => SetValue(ViewStartMsProperty, value); }
    public long         ViewEndMs   { get => (long)GetValue(ViewEndMsProperty);          set => SetValue(ViewEndMsProperty, value); }
    public long         CrosshairMs { get => (long)GetValue(CrosshairMsProperty);        set => SetValue(CrosshairMsProperty, value); }
    public long         MinViewMs   { get => (long)GetValue(MinViewMsProperty);          set => SetValue(MinViewMsProperty, value); }
    public long         MaxViewMs   { get => (long)GetValue(MaxViewMsProperty);          set => SetValue(MaxViewMsProperty, value); }

    // Bar chart: when set, bypasses the line-series path and uses this model directly.
    public static readonly DependencyProperty BarModelProperty =
        DependencyProperty.Register(nameof(BarModel), typeof(PlotModel), typeof(OxyChartControl),
            new PropertyMetadata(null, OnBarModelChanged));

    public PlotModel? BarModel { get => (PlotModel?)GetValue(BarModelProperty); set => SetValue(BarModelProperty, value); }

    private static void OnBarModelChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var ctrl = (OxyChartControl)d;
        if (e.NewValue is PlotModel m)
        {
            if (ctrl.Plot != null) ctrl.Plot.Model = m;
        }
        else
        {
            ctrl.RebuildModel();
        }
    }

    // ------------------------------------------------------------------ //
    //  Private state
    // ------------------------------------------------------------------ //

    private bool _suppressViewportSync;   // re-entrancy guard
    private bool _suppressCrosshairSync;

    private bool ViewportActive => ViewEndMs > ViewStartMs && ViewStartMs > 0;

    // ------------------------------------------------------------------ //
    //  Constructor
    // ------------------------------------------------------------------ //

    public OxyChartControl()
    {
        InitializeComponent();
        Loaded += (_, _) => RebuildModel();
    }

    // ------------------------------------------------------------------ //
    //  Property-change callbacks
    // ------------------------------------------------------------------ //

    private static void OnDataChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
        => ((OxyChartControl)d).RebuildModel();

    private static void OnLabelChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var ctrl = (OxyChartControl)d;
        ctrl.LabelText.Text = (string)e.NewValue;
    }

    private static void OnViewportChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var ctrl = (OxyChartControl)d;
        if (ctrl._suppressViewportSync) return;
        if (!ctrl.ViewportActive) { ctrl.RebuildModel(); return; }
        ctrl.ApplyViewportToAxis();
    }

    private static void OnCrosshairChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var ctrl = (OxyChartControl)d;
        if (ctrl._suppressCrosshairSync) return;
        ctrl.ApplyCrosshairToPlot();
    }

    // ------------------------------------------------------------------ //
    //  Model construction
    // ------------------------------------------------------------------ //

    private void RebuildModel()
    {
        if (Plot == null) return;

        // If a pre-built bar/column model is bound, keep it.
        if (BarModel != null) { Plot.Model = BarModel; return; }

        var data = ToDoubles(Values);
        var model = CreateBaseModel();
        LabelText.Text = Label;

        if (ViewportActive)
        {
            var ts = ToLongs(Timestamps);
            if (ts != null && ts.Count == data.Count && data.Count > 0)
            {
                BuildViewportModel(model, data, ts);
                Plot.Model = model;
                return;
            }
        }

        if (data.Count > 0)
            BuildSparklineModel(model, data);

        Plot.Model = model;
    }

    private static PlotModel CreateBaseModel()
    {
        return new PlotModel
        {
            IsLegendVisible        = false,
            PlotAreaBorderThickness = new OxyThickness(0),
            Background             = OxyColors.Transparent,
            PlotAreaBackground     = OxyColors.Transparent,
            Padding                = new OxyThickness(0),
        };
    }

    // ---- Sparkline mode ------------------------------------------------

    private void BuildSparklineModel(PlotModel model, List<double> data)
    {
        var c = LineColor;
        var oxy = OxyColor.FromArgb(c.A, c.R, c.G, c.B);

        var xAxis = new LinearAxis
        {
            Position        = AxisPosition.Bottom,
            IsAxisVisible   = false,
            IsPanEnabled    = false,
            IsZoomEnabled   = false,
        };
        var (min, max) = YRange(data, MinForced, MaxForced);
        var yAxis = new LinearAxis
        {
            Position        = AxisPosition.Left,
            IsAxisVisible   = false,
            IsPanEnabled    = false,
            IsZoomEnabled   = false,
            Minimum         = min,
            Maximum         = max,
        };
        model.Axes.Add(xAxis);
        model.Axes.Add(yAxis);

        var series = new AreaSeries
        {
            Color          = oxy,
            Fill           = OxyColor.FromArgb((byte)(c.A / 7), c.R, c.G, c.B),
            StrokeThickness = 1.6,
            TrackerFormatString = BuildTrackerFormat(),
        };
        for (int i = 0; i < data.Count; i++)
            series.Points.Add(new DataPoint(i, data[i]));
        model.Series.Add(series);
    }

    // ---- Viewport mode ------------------------------------------------

    private void BuildViewportModel(PlotModel model, List<double> data, List<long> ts)
    {
        var c = LineColor;
        var oxy = OxyColor.FromArgb(c.A, c.R, c.G, c.B);

        double startOa = MsToOaDate(ViewStartMs);
        double endOa   = MsToOaDate(ViewEndMs);

        var xAxis = new DateTimeAxis
        {
            Position             = AxisPosition.Bottom,
            IntervalType         = DateTimeIntervalType.Auto,
            StringFormat         = "HH:mm",
            MinimumPadding       = 0,
            MaximumPadding       = 0,
            IsPanEnabled         = true,
            IsZoomEnabled        = true,
            Minimum              = startOa,
            Maximum              = endOa,
            AxislineColor        = OxyColor.FromArgb(0x40, 0xE2, 0xE5, 0xEE),
            TicklineColor        = OxyColor.FromArgb(0x40, 0xE2, 0xE5, 0xEE),
            TextColor            = OxyColor.FromArgb(0xCC, 0x6B, 0x72, 0x80),
            FontSize             = 9.5,
        };

        // Clamp pan range when travel bounds are set.
        if (MinViewMs > 0) xAxis.AbsoluteMinimum = MsToOaDate(MinViewMs);
        if (MaxViewMs > 0) xAxis.AbsoluteMaximum = MsToOaDate(MaxViewMs);

        var (yMin, yMax) = YRangeForWindow(data, ts, ViewStartMs, ViewEndMs);
        var yAxis = new LinearAxis
        {
            Position         = AxisPosition.Left,
            Minimum          = yMin,
            Maximum          = yMax,
            IsPanEnabled     = false,
            IsZoomEnabled    = false,
            MinimumPadding   = 0.05,
            MaximumPadding   = 0.05,
            AxislineColor    = OxyColor.FromArgb(0x40, 0xE2, 0xE5, 0xEE),
            TicklineColor    = OxyColor.FromArgb(0x40, 0xE2, 0xE5, 0xEE),
            TextColor        = OxyColor.FromArgb(0xCC, 0x6B, 0x72, 0x80),
            FontSize         = 9.5,
        };

        model.Axes.Add(xAxis);
        model.Axes.Add(yAxis);

        // PlotModel.Updated fires after pan/zoom so we can push ViewStartMs/ViewEndMs back.
        // CS0618: OxyPlot 2.x marks this event [Obsolete] but does not remove it; suppress
        // the warning here rather than accepting a broken axis-sync or pulling a 3.x pre-release.
#pragma warning disable CS0618
        model.Updated += (_, _) => OnOxyModelUpdated(model);
#pragma warning restore CS0618

        var series = new AreaSeries
        {
            Color               = oxy,
            Fill                = OxyColor.FromArgb((byte)(c.A / 7), c.R, c.G, c.B),
            StrokeThickness     = 1.6,
            TrackerFormatString = BuildTrackerFormat(),
        };
        for (int i = 0; i < ts.Count; i++)
            series.Points.Add(new DataPoint(MsToOaDate(ts[i]), data[i]));
        model.Series.Add(series);
    }

    // ------------------------------------------------------------------ //
    //  Viewport <-> OxyPlot axis synchronisation
    // ------------------------------------------------------------------ //

    private void OnOxyModelUpdated(PlotModel model)
    {
        // Guard against re-entry when we ourselves push a change.
        if (_suppressViewportSync) return;

        var xAxis = model.Axes.OfType<DateTimeAxis>().FirstOrDefault();
        if (xAxis == null) return;

        long newStart = OaDateToMs(xAxis.ActualMinimum);
        long newEnd   = OaDateToMs(xAxis.ActualMaximum);

        if (newStart == ViewStartMs && newEnd == ViewEndMs) return;

        _suppressViewportSync = true;
        try
        {
            ViewStartMs = newStart;
            ViewEndMs   = newEnd;
        }
        finally
        {
            _suppressViewportSync = false;
        }
    }

    private void ApplyViewportToAxis()
    {
        if (Plot?.Model == null) return;
        var xAxis = Plot.Model.Axes.OfType<DateTimeAxis>().FirstOrDefault();
        if (xAxis == null) { RebuildModel(); return; }

        _suppressViewportSync = true;
        try
        {
            xAxis.Zoom(MsToOaDate(ViewStartMs), MsToOaDate(ViewEndMs));
            Plot.Model.InvalidatePlot(false);
        }
        finally
        {
            _suppressViewportSync = false;
        }
    }

    // ------------------------------------------------------------------ //
    //  Crosshair
    // ------------------------------------------------------------------ //

    private void ApplyCrosshairToPlot()
    {
        // We rely on OxyPlot's built-in tracker rather than drawing manually.
        // External CrosshairMs changes just invalidate the plot so the tracker
        // position reflects the nearest sample.
        Plot?.Model?.InvalidatePlot(false);
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        if (!ViewportActive || Plot?.Model == null) return;

        var pos = e.GetPosition(Plot);
        // Map pixel X to a time value using the DateTimeAxis.
        var xAxis = Plot.Model.Axes.OfType<DateTimeAxis>().FirstOrDefault();
        if (xAxis == null) return;

        // OxyPlot PlotView transform helper is not directly accessible without the
        // internal render context; instead we compute from axis min/max + control width.
        double plotWidth = Plot.ActualWidth;
        if (plotWidth <= 0) return;

        double frac = Math.Clamp(pos.X / plotWidth, 0.0, 1.0);
        double oaAtCursor = xAxis.ActualMinimum + frac * (xAxis.ActualMaximum - xAxis.ActualMinimum);
        long ms = OaDateToMs(oaAtCursor);

        if (ms == CrosshairMs) return;

        _suppressCrosshairSync = true;
        try { CrosshairMs = ms; }
        finally { _suppressCrosshairSync = false; }
    }

    protected override void OnMouseLeave(MouseEventArgs e)
    {
        base.OnMouseLeave(e);
        if (ViewportActive && CrosshairMs != 0)
        {
            _suppressCrosshairSync = true;
            try { CrosshairMs = 0; }
            finally { _suppressCrosshairSync = false; }
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    private string BuildTrackerFormat()
    {
        string unit = Unit;
        return string.IsNullOrEmpty(unit)
            ? "{2:0.##}"
            : "{2:0.##} " + unit;
    }

    private static (double min, double max) YRange(List<double> data, double? forcedMin, double? forcedMax)
    {
        double lo = forcedMin ?? data.Min();
        double hi = forcedMax ?? data.Max();
        if (hi <= lo) hi = lo + 1;
        double pad = Math.Max(0.5, hi - lo) * 0.1;
        return (lo - pad, hi + pad);
    }

    private (double min, double max) YRangeForWindow(
        List<double> data, List<long> ts, long start, long end)
    {
        if (MinForced.HasValue && MaxForced.HasValue)
            return (MinForced.Value, MaxForced.Value);

        double lo = double.MaxValue, hi = double.MinValue;
        for (int i = 0; i < data.Count; i++)
        {
            if (ts[i] < start || ts[i] > end) continue;
            if (data[i] < lo) lo = data[i];
            if (data[i] > hi) hi = data[i];
        }
        if (lo > hi) { lo = data.Min(); hi = data.Max(); }

        double pad = Math.Max(0.5, hi - lo) * 0.12;
        double mn = MinForced ?? (lo - pad);
        double mx = MaxForced ?? (hi + pad);
        if (mx <= mn) mx = mn + 1;
        return (mn, mx);
    }

    // OxyPlot DateTimeAxis uses OA dates internally.
    private static double MsToOaDate(long unixMs)
        => DateTimeAxis.ToDouble(DateTimeOffset.FromUnixTimeMilliseconds(unixMs).UtcDateTime);

    private static long OaDateToMs(double oa)
    {
        var dt = DateTimeAxis.ToDateTime(oa);
        return new DateTimeOffset(dt, TimeSpan.Zero).ToUnixTimeMilliseconds();
    }

    private static List<double> ToDoubles(IEnumerable? src)
    {
        var data = new List<double>();
        if (src == null) return data;
        foreach (var v in src)
        {
            if (v is double d && !double.IsNaN(d)) { data.Add(d); continue; }
            if (v is IConvertible cv)
            {
                try { data.Add(Convert.ToDouble(cv, CultureInfo.InvariantCulture)); } catch { }
            }
        }
        return data;
    }

    private static List<long>? ToLongs(IEnumerable? src)
    {
        if (src == null) return null;
        var data = new List<long>();
        foreach (var v in src)
        {
            if (v is long l) { data.Add(l); continue; }
            if (v is IConvertible cv)
            {
                try { data.Add(Convert.ToInt64(cv, CultureInfo.InvariantCulture)); } catch { }
            }
        }
        return data;
    }
}
