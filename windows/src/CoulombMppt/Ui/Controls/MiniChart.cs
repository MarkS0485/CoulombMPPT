using System.Collections;
using System.Globalization;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace CoulombMppt.Ui.Controls;

// Line chart for the History/Live pages — a normalised, gradient-filled
// polyline custom-drawn so the app keeps to its three-NuGet budget (no charting
// package). Two modes:
//
//  • Index mode (default): Values are spread evenly across the width and
//    auto-scaled to their own min/max. Hovering reads out the nearest sample.
//    This is what the Live page uses.
//
//  • Viewport mode (when ViewStartMs/ViewEndMs are set and a parallel
//    Timestamps series is supplied): points are placed by their real time, the
//    control draws value + time axes, and the user can drag to pan, wheel to
//    zoom, and hover to scrub. ViewStartMs/ViewEndMs/CrosshairMs bind two-way,
//    so a stack of these sharing the same bound properties pan, zoom and scrub
//    together. MinViewMs/MaxViewMs bound from the VM clamp how far it travels.
public sealed class MiniChart : FrameworkElement
{
    private const long MinSpanMs = 30_000L;
    private const long MaxSpanMs = 40L * 24 * 60 * 60 * 1000;

    public static readonly DependencyProperty ValuesProperty =
        DependencyProperty.Register(nameof(Values), typeof(IEnumerable), typeof(MiniChart),
            new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty TimestampsProperty =
        DependencyProperty.Register(nameof(Timestamps), typeof(IEnumerable), typeof(MiniChart),
            new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty LineColorProperty =
        DependencyProperty.Register(nameof(LineColor), typeof(Color), typeof(MiniChart),
            new FrameworkPropertyMetadata(Color.FromRgb(0x0F, 0x76, 0x6E),
                FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty MinForcedProperty =
        DependencyProperty.Register(nameof(MinForced), typeof(double?), typeof(MiniChart),
            new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty MaxForcedProperty =
        DependencyProperty.Register(nameof(MaxForced), typeof(double?), typeof(MiniChart),
            new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty UnitProperty =
        DependencyProperty.Register(nameof(Unit), typeof(string), typeof(MiniChart),
            new FrameworkPropertyMetadata("", FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty ValueFormatProperty =
        DependencyProperty.Register(nameof(ValueFormat), typeof(string), typeof(MiniChart),
            new FrameworkPropertyMetadata("0.##", FrameworkPropertyMetadataOptions.AffectsRender));

    // --- Viewport mode (all unix-ms). Two-way so a stack stays in lock-step. ---
    public static readonly DependencyProperty ViewStartMsProperty =
        DependencyProperty.Register(nameof(ViewStartMs), typeof(long), typeof(MiniChart),
            new FrameworkPropertyMetadata(0L,
                FrameworkPropertyMetadataOptions.AffectsRender |
                FrameworkPropertyMetadataOptions.BindsTwoWayByDefault));

    public static readonly DependencyProperty ViewEndMsProperty =
        DependencyProperty.Register(nameof(ViewEndMs), typeof(long), typeof(MiniChart),
            new FrameworkPropertyMetadata(0L,
                FrameworkPropertyMetadataOptions.AffectsRender |
                FrameworkPropertyMetadataOptions.BindsTwoWayByDefault));

    // Shared crosshair time; 0 = not scrubbing.
    public static readonly DependencyProperty CrosshairMsProperty =
        DependencyProperty.Register(nameof(CrosshairMs), typeof(long), typeof(MiniChart),
            new FrameworkPropertyMetadata(0L,
                FrameworkPropertyMetadataOptions.AffectsRender |
                FrameworkPropertyMetadataOptions.BindsTwoWayByDefault));

    // Travel clamps (oldest sample … now); 0 = unbounded.
    public static readonly DependencyProperty MinViewMsProperty =
        DependencyProperty.Register(nameof(MinViewMs), typeof(long), typeof(MiniChart),
            new FrameworkPropertyMetadata(0L));

    public static readonly DependencyProperty MaxViewMsProperty =
        DependencyProperty.Register(nameof(MaxViewMs), typeof(long), typeof(MiniChart),
            new FrameworkPropertyMetadata(0L));

    public IEnumerable? Values     { get => (IEnumerable?)GetValue(ValuesProperty); set => SetValue(ValuesProperty, value); }
    public IEnumerable? Timestamps { get => (IEnumerable?)GetValue(TimestampsProperty); set => SetValue(TimestampsProperty, value); }
    public Color   LineColor   { get => (Color)GetValue(LineColorProperty); set => SetValue(LineColorProperty, value); }
    public double? MinForced   { get => (double?)GetValue(MinForcedProperty); set => SetValue(MinForcedProperty, value); }
    public double? MaxForced   { get => (double?)GetValue(MaxForcedProperty); set => SetValue(MaxForcedProperty, value); }
    public string  Unit        { get => (string)GetValue(UnitProperty); set => SetValue(UnitProperty, value); }
    public string  ValueFormat { get => (string)GetValue(ValueFormatProperty); set => SetValue(ValueFormatProperty, value); }
    public long    ViewStartMs { get => (long)GetValue(ViewStartMsProperty); set => SetValue(ViewStartMsProperty, value); }
    public long    ViewEndMs   { get => (long)GetValue(ViewEndMsProperty); set => SetValue(ViewEndMsProperty, value); }
    public long    CrosshairMs { get => (long)GetValue(CrosshairMsProperty); set => SetValue(CrosshairMsProperty, value); }
    public long    MinViewMs   { get => (long)GetValue(MinViewMsProperty); set => SetValue(MinViewMsProperty, value); }
    public long    MaxViewMs   { get => (long)GetValue(MaxViewMsProperty); set => SetValue(MaxViewMsProperty, value); }

    private bool ViewportActive => ViewEndMs > ViewStartMs && ViewStartMs > 0;

    private double? _hoverX;        // cursor X while hovering (index mode)
    private Point? _dragLast;       // last drag position (viewport mode pan)

    // ==================================================================
    //  Input
    // ==================================================================

    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
    {
        base.OnMouseLeftButtonDown(e);
        if (!ViewportActive) return;
        _dragLast = e.GetPosition(this);
        CaptureMouse();
        Cursor = Cursors.ScrollWE;
    }

    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        base.OnMouseLeftButtonUp(e);
        _dragLast = null;
        ReleaseMouseCapture();
        Cursor = Cursors.Arrow;
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        var p = e.GetPosition(this);
        if (_dragLast is Point last && ViewportActive)
        {
            double dx = p.X - last.X;
            _dragLast = p;
            PanByPixels(dx);
            CrosshairMs = 0;            // hide readout while dragging
        }
        else
        {
            _hoverX = p.X;
            if (ViewportActive) CrosshairMs = TimeAtX(p.X);
            InvalidateVisual();
        }
    }

    protected override void OnMouseLeave(MouseEventArgs e)
    {
        base.OnMouseLeave(e);
        _hoverX = null;
        _dragLast = null;
        if (ViewportActive) CrosshairMs = 0;
        InvalidateVisual();
    }

    protected override void OnMouseWheel(MouseWheelEventArgs e)
    {
        base.OnMouseWheel(e);
        if (!ViewportActive) return;
        double factor = e.Delta > 0 ? 1.25 : 1 / 1.25;
        ZoomAt(e.GetPosition(this).X, factor);
        e.Handled = true;
    }

    private (double l, double r, double t, double b) Pads() =>
        ViewportActive ? (40, 6, 8, 16) : (6, 6, 6, 6);

    private long TimeAtX(double x)
    {
        var (pl, pr, _, _) = Pads();
        double plotW = Math.Max(1, ActualWidth - pl - pr);
        double frac = Math.Clamp((x - pl) / plotW, 0, 1);
        return ViewStartMs + (long)(frac * (ViewEndMs - ViewStartMs));
    }

    private void PanByPixels(double dx)
    {
        var (pl, pr, _, _) = Pads();
        double plotW = Math.Max(1, ActualWidth - pl - pr);
        long span = ViewEndMs - ViewStartMs;
        long shift = (long)(-dx / plotW * span);
        SetViewport(ViewStartMs + shift, ViewEndMs + shift);
    }

    private void ZoomAt(double x, double factor)
    {
        var (pl, pr, _, _) = Pads();
        double plotW = Math.Max(1, ActualWidth - pl - pr);
        long span = ViewEndMs - ViewStartMs;
        double frac = Math.Clamp((x - pl) / plotW, 0, 1);
        long newSpan = Math.Clamp((long)(span / factor), MinSpanMs, MaxSpan());
        long focal = ViewStartMs + (long)(frac * span);
        long ns = focal - (long)(frac * newSpan);
        SetViewport(ns, ns + newSpan);
    }

    private long MaxSpan() =>
        (MaxViewMs > 0 && MinViewMs > 0) ? Math.Max(MinSpanMs, MaxViewMs - MinViewMs) : MaxSpanMs;

    private void SetViewport(long s, long e)
    {
        long span = Math.Clamp(e - s, MinSpanMs, MaxSpan());
        long ns = s, ne = s + span;
        long maxEnd = MaxViewMs > 0 ? MaxViewMs : ne;
        long minStart = MinViewMs > 0 ? MinViewMs : ns;
        if (ne > maxEnd) { ne = maxEnd; ns = ne - span; }
        if (ns < minStart) { ns = minStart; ne = Math.Min(ns + span, maxEnd); }
        ViewStartMs = ns;
        ViewEndMs = ne;
    }

    // ==================================================================
    //  Render
    // ==================================================================

    protected override void OnRender(DrawingContext drawingContext)
    {
        var dc = drawingContext;
        double w = ActualWidth, h = ActualHeight;
        if (w <= 0 || h <= 0) return;

        dc.DrawRectangle(Brush(0xFFF6F7FB), null, new Rect(0, 0, w, h));

        var data = ToDoubles(Values);
        if (data.Count < 2)
        {
            var msg = new FormattedText("no data",
                CultureInfo.CurrentCulture, FlowDirection.LeftToRight,
                new Typeface("Segoe UI"), 12, Brush(0xFF9CA3AF), 1.0);
            dc.DrawText(msg, new Point(w / 2 - msg.Width / 2, h / 2 - msg.Height / 2));
            return;
        }

        if (ViewportActive)
        {
            var ts = ToLongs(Timestamps);
            if (ts != null && ts.Count == data.Count) { RenderViewport(dc, data, ts, w, h); return; }
        }
        RenderIndex(dc, data, w, h);
    }

    // ---- Index mode (legacy: even spread, hover tooltip) ----
    private void RenderIndex(DrawingContext dc, List<double> data, double w, double h)
    {
        double min = MinForced ?? data.Min();
        double max = MaxForced ?? data.Max();
        if (max <= min) max = min + 1;

        const double pad = 6;
        double plotW = w - pad * 2, plotH = h - pad * 2;
        Point Map(int i, double v) => new(
            pad + plotW * i / (data.Count - 1),
            pad + plotH * (1 - (v - min) / (max - min)));

        DrawSeries(dc, data, Map, pad, w, h);

        if (_hoverX is double hx)
        {
            double clampedX = Math.Clamp(hx, pad, w - pad);
            int idx = Math.Clamp((int)Math.Round((clampedX - pad) / plotW * (data.Count - 1)), 0, data.Count - 1);
            var ts = ToLongs(Timestamps);
            long? t = (ts != null && idx < ts.Count) ? ts[idx] : null;
            DrawCrosshair(dc, Map(idx, data[idx]), data[idx], t, pad, w, h);
        }
    }

    // ---- Viewport mode (time-mapped, axes, pan/zoom, shared crosshair) ----
    private void RenderViewport(DrawingContext dc, List<double> data, List<long> ts, double w, double h)
    {
        var (pl, pr, pt, pb) = Pads();
        double plotW = Math.Max(1, w - pl - pr);
        double plotH = Math.Max(1, h - pt - pb);
        long start = ViewStartMs, end = ViewEndMs, span = Math.Max(1, end - start);

        // Value range over what's visible.
        double min, max;
        if (MinForced is double mf && MaxForced is double xf) { min = mf; max = xf; }
        else
        {
            double lo = double.MaxValue, hi = double.MinValue;
            for (int i = 0; i < data.Count; i++)
                if (ts[i] >= start && ts[i] <= end) { lo = Math.Min(lo, data[i]); hi = Math.Max(hi, data[i]); }
            if (lo > hi) { lo = data.Min(); hi = data.Max(); }
            double padv = Math.Max(0.5, hi - lo) * 0.12;
            min = MinForced ?? (lo - padv);
            max = MaxForced ?? (hi + padv);
        }
        if (max <= min) max = min + 1;

        double X(long t) => pl + plotW * ((double)(t - start) / span);
        double Y(double v) => pt + plotH * (1 - Math.Clamp((v - min) / (max - min), 0, 1));
        var labelBrush = Brush(0xFF6B7280);
        var grid = Brush(0xFFE2E5EE);

        // Value gridlines + labels.
        foreach (var tick in NiceValueTicks(min, max, 4))
        {
            double y = Y(tick);
            if (y < pt - 1 || y > pt + plotH + 1) continue;
            dc.DrawLine(new Pen(grid, 1), new Point(pl, y), new Point(pl + plotW, y));
            var txt = FormatTick(tick);
            var ft = Label(txt, labelBrush);
            dc.DrawText(ft, new Point(pl - ft.Width - 4, y - ft.Height / 2));
        }

        // Time gridlines + labels.
        var timeGridPen = new Pen(grid, 1) { DashStyle = new DashStyle(new double[] { 3, 4 }, 0) };
        foreach (var tick in NiceTimeTicks(start, end, 5))
        {
            double x = X(tick);
            if (x < pl - 1 || x > pl + plotW + 1) continue;
            dc.DrawLine(timeGridPen, new Point(x, pt), new Point(x, pt + plotH));
            var ft = Label(TimeLabel(tick, span, false), labelBrush);
            double tx = Math.Clamp(x - ft.Width / 2, pl, pl + plotW - ft.Width);
            dc.DrawText(ft, new Point(tx, pt + plotH + 2));
        }

        // Series, clipped to the plot rectangle.
        dc.PushClip(new RectangleGeometry(new Rect(pl, pt, plotW, plotH)));
        var line = new PathFigure { IsClosed = false };
        var fill = new PathFigure { IsClosed = true };
        bool started = false;
        double lastX = 0;
        for (int i = 0; i < data.Count; i++)
        {
            // Keep one point of margin either side so the line enters/exits cleanly.
            if (ts[i] < start - span || ts[i] > end + span) continue;
            var p = new Point(X(ts[i]), Y(data[i]));
            if (!started)
            {
                line.StartPoint = p;
                fill.StartPoint = new Point(p.X, pt + plotH);
                fill.Segments.Add(new LineSegment(p, true));
                lastX = p.X; started = true;
            }
            else { line.Segments.Add(new LineSegment(p, true)); fill.Segments.Add(new LineSegment(p, true)); lastX = p.X; }
        }
        if (started)
        {
            fill.Segments.Add(new LineSegment(new Point(lastX, pt + plotH), true));
            var fillGeo = new PathGeometry(); fillGeo.Figures.Add(fill);
            var fillBrush = new SolidColorBrush(Color.FromArgb(0x24, LineColor.R, LineColor.G, LineColor.B));
            fillBrush.Freeze();
            dc.DrawGeometry(fillBrush, null, fillGeo);
            var lineGeo = new PathGeometry(); lineGeo.Figures.Add(line);
            dc.DrawGeometry(null, new Pen(new SolidColorBrush(LineColor), 1.6) { LineJoin = PenLineJoin.Round }, lineGeo);
        }
        dc.Pop();

        // Shared crosshair at CrosshairMs.
        if (CrosshairMs >= start && CrosshairMs <= end)
        {
            int idx = NearestByTime(ts, CrosshairMs);
            if (idx >= 0)
                DrawCrosshairAt(dc, new Point(X(ts[idx]), Y(data[idx])), data[idx], ts[idx], pt, pt + plotH, w, h);
        }
    }

    // ==================================================================
    //  Shared drawing helpers
    // ==================================================================

    private void DrawSeries(DrawingContext dc, List<double> data, Func<int, double, Point> map,
                            double pad, double w, double h)
    {
        var line = new PathFigure { StartPoint = map(0, data[0]), IsClosed = false };
        for (int i = 1; i < data.Count; i++) line.Segments.Add(new LineSegment(map(i, data[i]), true));

        var fill = new PathFigure { StartPoint = new Point(pad, h - pad), IsClosed = true };
        fill.Segments.Add(new LineSegment(map(0, data[0]), true));
        for (int i = 1; i < data.Count; i++) fill.Segments.Add(new LineSegment(map(i, data[i]), true));
        fill.Segments.Add(new LineSegment(new Point(w - pad, h - pad), true));
        var fillGeo = new PathGeometry(); fillGeo.Figures.Add(fill);
        var fillBrush = new SolidColorBrush(Color.FromArgb(0x22, LineColor.R, LineColor.G, LineColor.B));
        fillBrush.Freeze();
        dc.DrawGeometry(fillBrush, null, fillGeo);

        var lineGeo = new PathGeometry(); lineGeo.Figures.Add(line);
        dc.DrawGeometry(null, new Pen(new SolidColorBrush(LineColor), 1.6) { LineJoin = PenLineJoin.Round }, lineGeo);
    }

    private void DrawCrosshair(DrawingContext dc, Point pt, double value, long? t, double pad, double w, double h)
        => DrawCrosshairAt(dc, pt, value, t, pad, h - pad, w, h);

    private void DrawCrosshairAt(DrawingContext dc, Point pt, double value, long? t,
                                 double top, double bottom, double w, double h)
    {
        var crossPen = new Pen(Brush(0x886B7280), 1) { DashStyle = new DashStyle(new double[] { 3, 3 }, 0) };
        dc.DrawLine(crossPen, new Point(pt.X, top), new Point(pt.X, bottom));

        var dotBrush = new SolidColorBrush(LineColor); dotBrush.Freeze();
        dc.DrawEllipse(dotBrush, new Pen(Brush(0xFFFFFFFF), 1.5), pt, 3.5, 3.5);

        string valText = value.ToString(ValueFormat, CultureInfo.CurrentCulture);
        if (!string.IsNullOrEmpty(Unit)) valText += " " + Unit;
        string label = t is long ms
            ? $"{valText}\n{DateTimeOffset.FromUnixTimeMilliseconds(ms).ToLocalTime():HH:mm:ss}"
            : valText;

        var text = new FormattedText(label, CultureInfo.CurrentCulture, FlowDirection.LeftToRight,
            new Typeface("Segoe UI"), 11, Brush(0xFFFFFFFF), 1.0);

        const double tp = 5;
        double boxW = text.Width + tp * 2, boxH = text.Height + tp * 2;
        double bx = pt.X + 10, by = pt.Y - boxH - 8;
        if (bx + boxW > w) bx = pt.X - boxW - 10;
        if (bx < 0) bx = 0;
        if (by < 0) by = pt.Y + 10;
        if (by + boxH > h) by = h - boxH;

        var bg = new SolidColorBrush(Color.FromArgb(0xF0, 0x11, 0x18, 0x27)); bg.Freeze();
        dc.DrawRoundedRectangle(bg, null, new Rect(bx, by, boxW, boxH), 4, 4);
        dc.DrawText(text, new Point(bx + tp, by + tp));
    }

    private static FormattedText Label(string s, Brush b) => new(
        s, CultureInfo.CurrentCulture, FlowDirection.LeftToRight, new Typeface("Segoe UI"), 9.5, b, 1.0);

    private static int NearestByTime(List<long> ts, long target)
    {
        if (ts.Count == 0) return -1;
        int best = 0; long bestD = Math.Abs(ts[0] - target);
        for (int i = 1; i < ts.Count; i++)
        {
            long d = Math.Abs(ts[i] - target);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private static List<double> NiceValueTicks(double min, double max, int target)
    {
        var ticks = new List<double>();
        if (max <= min) { ticks.Add(min); return ticks; }
        double raw = (max - min) / target;
        double mag = Math.Pow(10, Math.Floor(Math.Log10(raw)));
        double norm = raw / mag;
        double step = (norm < 1.5 ? 1 : norm < 3 ? 2 : norm < 7 ? 5 : 10) * mag;
        for (double t = Math.Ceiling(min / step) * step; t <= max && ticks.Count < 20; t += step) ticks.Add(t);
        return ticks;
    }

    private static string FormatTick(double v) =>
        Math.Abs(v) >= 100 || v == Math.Floor(v)
            ? v.ToString("0", CultureInfo.CurrentCulture)
            : v.ToString("0.#", CultureInfo.CurrentCulture);

    private static readonly long[] TimeSteps =
    {
        1000, 5000, 10000, 30000, 60000, 300000, 900000, 1800000,
        3600000, 3 * 3600000L, 6 * 3600000L, 12 * 3600000L,
        86400000L, 2 * 86400000L, 7 * 86400000L, 30 * 86400000L,
    };

    private static List<long> NiceTimeTicks(long start, long end, int target)
    {
        long span = Math.Max(1, end - start);
        long step = TimeSteps.FirstOrDefault(s => span / s <= target);
        if (step == 0) step = TimeSteps[^1];
        var ticks = new List<long>();
        long first = (start / step) * step;
        for (long t = first < start ? first + step : first; t <= end && ticks.Count < 40; t += step) ticks.Add(t);
        return ticks;
    }

    private static string TimeLabel(long ms, long span, bool seconds)
    {
        string fmt =
            span < 2 * 60_000L || (seconds && span < 3_600_000L) ? "HH:mm:ss" :
            span < 86_400_000L ? "HH:mm" :
            span < 7 * 86_400_000L ? "ddd HH:mm" :
            span < 60 * 86_400_000L ? "d MMM" : "MMM yy";
        return DateTimeOffset.FromUnixTimeMilliseconds(ms).ToLocalTime().ToString(fmt, CultureInfo.CurrentCulture);
    }

    private static List<double> ToDoubles(IEnumerable? src)
    {
        var data = new List<double>();
        if (src != null)
            foreach (var v in src)
                if (v is double d && !double.IsNaN(d)) data.Add(d);
                else if (v is IConvertible cv) { try { data.Add(Convert.ToDouble(cv, CultureInfo.InvariantCulture)); } catch { } }
        return data;
    }

    private static List<long>? ToLongs(IEnumerable? src)
    {
        if (src == null) return null;
        var data = new List<long>();
        foreach (var v in src)
            if (v is long l) data.Add(l);
            else if (v is IConvertible cv) { try { data.Add(Convert.ToInt64(cv, CultureInfo.InvariantCulture)); } catch { } }
        return data;
    }

    private static SolidColorBrush Brush(uint argb)
    {
        var b = new SolidColorBrush(Color.FromArgb(
            (byte)((argb >> 24) & 0xFF), (byte)((argb >> 16) & 0xFF),
            (byte)((argb >> 8) & 0xFF), (byte)(argb & 0xFF)));
        b.Freeze();
        return b;
    }
}
