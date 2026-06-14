using System.Globalization;
using System.Windows;
using System.Windows.Media;

namespace CoulombMppt.Ui.Controls;

// Custom-drawn 270° radial gauge (no XAML, no charting dependency) matching the
// SocRing style. Two modes:
//   • Unidirectional (default): value sweeps from the lower-left (Min) clockwise
//     to the lower-right (Max).
//   • Bidirectional: zero sits at 12 o'clock; positive values sweep right,
//     negative values sweep left — used for battery / inverter current where
//     sign means charge vs. draw.
//
// Renders: faint full track, a coloured value arc with rounded caps, the big
// numeric value + unit in the centre, the label underneath, and Min/Max ticks.
public sealed class RadialGauge : FrameworkElement
{
    // The 270° arc: starts at 135° (≈ 7:30) and sweeps clockwise 270° to 405°
    // (≈ 4:30), leaving the bottom 90° open. Angles use screen convention
    // (0°=3 o'clock, +clockwise, −90°/270°=12 o'clock).
    private const double TrackStart = 135.0;
    private const double TrackSweep = 270.0;
    private const double TopAngle   = 270.0;   // 12 o'clock for bidirectional zero
    private const double HalfSweep  = 135.0;

    public static readonly DependencyProperty ValueProperty =
        DP(nameof(Value), 0.0);
    public static readonly DependencyProperty MinProperty =
        DP(nameof(Min), 0.0);
    public static readonly DependencyProperty MaxProperty =
        DP(nameof(Max), 100.0);
    public static readonly DependencyProperty UnitProperty =
        DP(nameof(Unit), "");
    public static readonly DependencyProperty LabelProperty =
        DP(nameof(Label), "");
    public static readonly DependencyProperty ValueFormatProperty =
        DP(nameof(ValueFormat), "0.#");
    public static readonly DependencyProperty AccentProperty =
        DP(nameof(Accent), Color.FromRgb(0x0F, 0x76, 0x6E));
    public static readonly DependencyProperty NegativeAccentProperty =
        DP(nameof(NegativeAccent), Color.FromRgb(0xB4, 0x53, 0x09));
    public static readonly DependencyProperty BidirectionalProperty =
        DP(nameof(Bidirectional), false);
    public static readonly DependencyProperty ThicknessProperty =
        DP(nameof(Thickness), 12.0);

    public double Value          { get => (double)GetValue(ValueProperty); set => SetValue(ValueProperty, value); }
    public double Min            { get => (double)GetValue(MinProperty); set => SetValue(MinProperty, value); }
    public double Max            { get => (double)GetValue(MaxProperty); set => SetValue(MaxProperty, value); }
    public string Unit           { get => (string)GetValue(UnitProperty); set => SetValue(UnitProperty, value); }
    public string Label          { get => (string)GetValue(LabelProperty); set => SetValue(LabelProperty, value); }
    public string ValueFormat    { get => (string)GetValue(ValueFormatProperty); set => SetValue(ValueFormatProperty, value); }
    public Color  Accent         { get => (Color)GetValue(AccentProperty); set => SetValue(AccentProperty, value); }
    public Color  NegativeAccent { get => (Color)GetValue(NegativeAccentProperty); set => SetValue(NegativeAccentProperty, value); }
    public bool   Bidirectional  { get => (bool)GetValue(BidirectionalProperty); set => SetValue(BidirectionalProperty, value); }
    public double Thickness      { get => (double)GetValue(ThicknessProperty); set => SetValue(ThicknessProperty, value); }

    private static DependencyProperty DP(string name, object def) =>
        DependencyProperty.Register(name, def.GetType(), typeof(RadialGauge),
            new FrameworkPropertyMetadata(def, FrameworkPropertyMetadataOptions.AffectsRender));

    protected override void OnRender(DrawingContext drawingContext)
    {
        var dc = drawingContext;
        double w = ActualWidth, h = ActualHeight;
        if (w <= 0 || h <= 0) return;

        double size = Math.Min(w, h);
        // Bias the circle slightly up so the bottom label has room.
        var center = new Point(w / 2, h / 2 - size * 0.04);
        double radius = (size - Thickness) / 2 - 2;
        if (radius <= 0) return;

        double min = Min, max = Max;
        if (max <= min) max = min + 1;
        double val = double.IsNaN(Value) ? 0 : Value;

        // Track.
        var trackBrush = Brush(0xFFE8EBF2);
        DrawArc(dc, center, radius, TrackStart, TrackStart + TrackSweep, trackBrush, Thickness, rounded: true);

        // Value arc.
        if (Bidirectional)
        {
            double span = Math.Max(Math.Abs(min), Math.Abs(max));
            if (span <= 0) span = 1;
            double f = Math.Clamp(val / span, -1, 1);
            var color = val >= 0 ? Accent : NegativeAccent;
            if (Math.Abs(f) > 0.005)
            {
                double end = TopAngle + f * HalfSweep;
                DrawArc(dc, center, radius, TopAngle, end, new SolidColorBrush(color), Thickness, rounded: true);
            }
            // Zero tick at top.
            var tickPen = new Pen(Brush(0xFFCBD2DF), 2);
            var p0 = PointOnCircle(center, radius + Thickness / 2 + 2, TopAngle);
            var p1 = PointOnCircle(center, radius - Thickness / 2 - 2, TopAngle);
            dc.DrawLine(tickPen, p0, p1);
        }
        else
        {
            double f = Math.Clamp((val - min) / (max - min), 0, 1);
            if (f > 0.005)
            {
                double end = TrackStart + f * TrackSweep;
                DrawArc(dc, center, radius, TrackStart, end, new SolidColorBrush(Accent), Thickness, rounded: true);
            }
        }

        // Centre value (big) + unit.
        string valText = val.ToString(ValueFormat, CultureInfo.CurrentCulture);
        if (Bidirectional && val > 0) valText = "+" + valText;
        var big = Text(valText, Math.Max(15, size * 0.20), 0xFF0F172A, FontWeights.SemiBold);
        var unit = string.IsNullOrEmpty(Unit) ? null
            : Text(Unit, Math.Max(9, size * 0.085), 0xFF6B7280, FontWeights.Normal);

        double totalW = big.Width + (unit != null ? unit.Width + 3 : 0);
        double bx = center.X - totalW / 2;
        dc.DrawText(big, new Point(bx, center.Y - big.Height / 2 - size * 0.02));
        if (unit != null)
            dc.DrawText(unit, new Point(bx + big.Width + 3, center.Y - big.Height / 2 + big.Height - unit.Height - size * 0.02));

        // Label under the value.
        if (!string.IsNullOrEmpty(Label))
        {
            var lab = Text(Label, Math.Max(9, size * 0.082), 0xFF6B7280, FontWeights.Normal);
            dc.DrawText(lab, new Point(center.X - lab.Width / 2, center.Y + size * 0.13));
        }

        // Min / Max tick labels at the arc ends (unidirectional only).
        if (!Bidirectional)
        {
            var minT = Text(FormatTick(min), Math.Max(8, size * 0.07), 0xFF9CA3AF, FontWeights.Normal);
            var maxT = Text(FormatTick(max), Math.Max(8, size * 0.07), 0xFF9CA3AF, FontWeights.Normal);
            var mp = PointOnCircle(center, radius, TrackStart);
            var xp = PointOnCircle(center, radius, TrackStart + TrackSweep);
            dc.DrawText(minT, new Point(mp.X - minT.Width / 2, mp.Y + 2));
            dc.DrawText(maxT, new Point(xp.X - maxT.Width / 2, xp.Y + 2));
        }
    }

    private static void DrawArc(DrawingContext dc, Point center, double radius,
                                double startAngle, double endAngle, Brush brush, double thickness, bool rounded)
    {
        var pen = new Pen(brush, thickness);
        if (rounded) { pen.StartLineCap = PenLineCap.Round; pen.EndLineCap = PenLineCap.Round; }
        double sweep = endAngle - startAngle;
        var dir = sweep >= 0 ? SweepDirection.Clockwise : SweepDirection.Counterclockwise;
        var start = PointOnCircle(center, radius, startAngle);
        var end   = PointOnCircle(center, radius, endAngle);
        var fig = new PathFigure { StartPoint = start, IsClosed = false };
        fig.Segments.Add(new ArcSegment(end, new Size(radius, radius), 0,
            Math.Abs(sweep) > 180, dir, true));
        var geo = new PathGeometry();
        geo.Figures.Add(fig);
        dc.DrawGeometry(null, pen, geo);
    }

    private static Point PointOnCircle(Point c, double r, double angleDeg)
    {
        double rad = angleDeg * Math.PI / 180.0;
        return new Point(c.X + r * Math.Cos(rad), c.Y + r * Math.Sin(rad));
    }

    private static FormattedText Text(string s, double size, uint argb, FontWeight weight) => new(
        s, CultureInfo.CurrentCulture, FlowDirection.LeftToRight,
        new Typeface(new FontFamily("Segoe UI"), FontStyles.Normal, weight, FontStretches.Normal),
        size, Brush(argb), 1.0);

    private static string FormatTick(double v) =>
        Math.Abs(v) >= 100 || v == Math.Floor(v)
            ? v.ToString("0", CultureInfo.CurrentCulture)
            : v.ToString("0.#", CultureInfo.CurrentCulture);

    private static SolidColorBrush Brush(uint argb)
    {
        var b = new SolidColorBrush(Color.FromArgb(
            (byte)((argb >> 24) & 0xFF), (byte)((argb >> 16) & 0xFF),
            (byte)((argb >> 8) & 0xFF), (byte)(argb & 0xFF)));
        b.Freeze();
        return b;
    }
}
