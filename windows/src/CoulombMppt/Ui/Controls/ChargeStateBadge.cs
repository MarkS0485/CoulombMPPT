using System.Globalization;
using System.Windows;
using System.Windows.Media;
using CoulombMppt.Data;

namespace CoulombMppt.Ui.Controls;

// A compact, colour-coded charger-state pill (the WPF answer to the Android
// ChargeStateBadge): a tinted rounded chip with a leading dot and the state
// label, coloured by what the charger is doing — green while charging, amber
// when the load is off, red on fault, grey when idle/unknown. Custom-drawn so it
// stays dependency-free and sizes itself to its own text inside a StackPanel.
public sealed class ChargeStateBadge : FrameworkElement
{
    public static readonly DependencyProperty StateProperty =
        DependencyProperty.Register(nameof(State), typeof(ChargerState), typeof(ChargeStateBadge),
            new FrameworkPropertyMetadata(ChargerState.Unknown,
                FrameworkPropertyMetadataOptions.AffectsRender | FrameworkPropertyMetadataOptions.AffectsMeasure));

    public ChargerState State { get => (ChargerState)GetValue(StateProperty); set => SetValue(StateProperty, value); }

    private const double PadX = 11, PadY = 5, Gap = 7, DotR = 4.5, FontSz = 12.5;

    private FormattedText LabelText()
    {
        var t = new FormattedText(State.Label(), CultureInfo.CurrentCulture, FlowDirection.LeftToRight,
            new Typeface(new FontFamily("Segoe UI"), FontStyles.Normal, FontWeights.SemiBold, FontStretches.Normal),
            FontSz, Brush(State), 1.0);
        return t;
    }

    protected override Size MeasureOverride(Size availableSize)
    {
        var t = LabelText();
        return new Size(PadX + DotR * 2 + Gap + t.Width + PadX,
                        Math.Max(t.Height, DotR * 2) + PadY * 2);
    }

    protected override void OnRender(DrawingContext drawingContext)
    {
        double w = ActualWidth, h = ActualHeight;
        if (w <= 0 || h <= 0) return;

        var color = AccentColor(State);
        var bg = new SolidColorBrush(Color.FromArgb(0x22, color.R, color.G, color.B)); bg.Freeze();
        drawingContext.DrawRoundedRectangle(bg, null, new Rect(0, 0, w, h), h / 2, h / 2);

        var dot = new SolidColorBrush(color); dot.Freeze();
        double cy = h / 2;
        drawingContext.DrawEllipse(dot, null, new Point(PadX + DotR, cy), DotR, DotR);

        var t = LabelText();
        drawingContext.DrawText(t, new Point(PadX + DotR * 2 + Gap, cy - t.Height / 2));
    }

    private static SolidColorBrush Brush(ChargerState s)
    {
        var b = new SolidColorBrush(AccentColor(s));
        b.Freeze();
        return b;
    }

    private static Color AccentColor(ChargerState s) => s switch
    {
        ChargerState.Bulk or ChargerState.Boost or ChargerState.Floating => Color.FromRgb(0x16, 0xA3, 0x4A), // green — charging
        ChargerState.LoadOff => Color.FromRgb(0xD9, 0x77, 0x06), // amber — load off
        ChargerState.Fault   => Color.FromRgb(0xC8, 0x10, 0x2E), // red — fault
        _                    => Color.FromRgb(0x6B, 0x72, 0x80), // grey — idle / unknown
    };
}
