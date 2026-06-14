using System.Globalization;
using System.Windows;
using System.Windows.Media;

namespace CoulombMppt.Ui.Controls;

// Lightweight circular state-of-charge gauge. Custom-drawn (no XAML) so it stays
// dependency-free and re-renders cheaply when the bound percent changes. The
// track is a faint full ring; the value arc sweeps clockwise from 12 o'clock.
public sealed class SocRing : FrameworkElement
{
    public static readonly DependencyProperty PercentProperty =
        DependencyProperty.Register(nameof(Percent), typeof(double), typeof(SocRing),
            new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty CaptionProperty =
        DependencyProperty.Register(nameof(Caption), typeof(string), typeof(SocRing),
            new FrameworkPropertyMetadata("SoC", FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty ThicknessProperty =
        DependencyProperty.Register(nameof(Thickness), typeof(double), typeof(SocRing),
            new FrameworkPropertyMetadata(14.0, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty ShowValueProperty =
        DependencyProperty.Register(nameof(ShowValue), typeof(bool), typeof(SocRing),
            new FrameworkPropertyMetadata(true, FrameworkPropertyMetadataOptions.AffectsRender));

    public double Percent   { get => (double)GetValue(PercentProperty);   set => SetValue(PercentProperty, value); }
    public string Caption   { get => (string)GetValue(CaptionProperty);   set => SetValue(CaptionProperty, value); }
    public double Thickness { get => (double)GetValue(ThicknessProperty); set => SetValue(ThicknessProperty, value); }
    public bool   ShowValue { get => (bool)GetValue(ShowValueProperty);   set => SetValue(ShowValueProperty, value); }

    protected override void OnRender(DrawingContext drawingContext)
    {
        double w = ActualWidth, h = ActualHeight;
        if (w <= 0 || h <= 0) return;

        double pct = Math.Clamp(double.IsNaN(Percent) ? 0 : Percent, 0, 100);
        double size = Math.Min(w, h);
        var center = new Point(w / 2, h / 2);
        double radius = (size - Thickness) / 2;
        if (radius <= 0) return;

        // Track.
        var trackPen = new Pen(Brush(0xFFE2E5EE), Thickness);
        drawingContext.DrawEllipse(null, trackPen, center, radius, radius);

        // Value arc — colour ramps from red (empty) through amber to green (full).
        var color = RampColor(pct);
        var valuePen = new Pen(new SolidColorBrush(color), Thickness)
        {
            StartLineCap = PenLineCap.Round,
            EndLineCap   = PenLineCap.Round,
        };

        if (pct > 0.01)
        {
            double sweep = pct / 100.0 * 360.0;
            var start = PointOnCircle(center, radius, -90);
            var end   = PointOnCircle(center, radius, -90 + sweep);
            var fig = new PathFigure { StartPoint = start, IsClosed = false };
            fig.Segments.Add(new ArcSegment(
                end,
                new Size(radius, radius),
                0,
                sweep > 180,            // large-arc flag
                SweepDirection.Clockwise,
                true));
            var geo = new PathGeometry();
            geo.Figures.Add(fig);
            drawingContext.DrawGeometry(null, valuePen, geo);
        }

        if (!ShowValue) return;

        // Centre value + caption.
        var big = new FormattedText(
            $"{pct:0}%",
            CultureInfo.CurrentCulture, FlowDirection.LeftToRight,
            new Typeface(new FontFamily("Segoe UI"), FontStyles.Normal, FontWeights.SemiBold, FontStretches.Normal),
            Math.Max(14, size * 0.22), Brush(0xFF0F172A), 1.0);
        drawingContext.DrawText(big, new Point(center.X - big.Width / 2, center.Y - big.Height / 2 - size * 0.05));

        if (!string.IsNullOrEmpty(Caption))
        {
            var cap = new FormattedText(
                Caption,
                CultureInfo.CurrentCulture, FlowDirection.LeftToRight,
                new Typeface(new FontFamily("Segoe UI"), FontStyles.Normal, FontWeights.Normal, FontStretches.Normal),
                Math.Max(9, size * 0.085), Brush(0xFF6B7280), 1.0);
            drawingContext.DrawText(cap, new Point(center.X - cap.Width / 2, center.Y + size * 0.10));
        }
    }

    private static Point PointOnCircle(Point c, double r, double angleDeg)
    {
        double rad = angleDeg * Math.PI / 180.0;
        return new Point(c.X + r * Math.Cos(rad), c.Y + r * Math.Sin(rad));
    }

    // Red < 25, amber < 60, green otherwise — same intent as the alert thresholds.
    private static Color RampColor(double pct) => pct switch
    {
        < 25 => Color.FromRgb(0xC8, 0x10, 0x2E),
        < 60 => Color.FromRgb(0xD9, 0x77, 0x06),
        _    => Color.FromRgb(0x16, 0xA3, 0x4A),
    };

    private static SolidColorBrush Brush(uint argb)
    {
        var b = new SolidColorBrush(Color.FromArgb(
            (byte)((argb >> 24) & 0xFF), (byte)((argb >> 16) & 0xFF),
            (byte)((argb >> 8) & 0xFF), (byte)(argb & 0xFF)));
        b.Freeze();
        return b;
    }
}
