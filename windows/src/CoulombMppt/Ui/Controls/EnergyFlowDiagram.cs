using System.Globalization;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;

namespace CoulombMppt.Ui.Controls;

// The dashboard centrepiece: a live, animated power-flow picture (the WPF answer
// to the Android EnergyFlow composable). Three nodes — Solar array, the battery
// bank, and the EA SUN inverter/load — joined by connectors whose thickness and
// animated "marching dots" show how much power is moving and which way.
//
//   ☀ Solar ──▶ 🔋 Battery ◀──▶ ⚡ EA SUN
//
//  • Solar → Battery: the sensed MPPT charge, always left-to-right.
//  • Battery ↔ EA SUN: the *inferred* EA SUN contribution. Positive means its
//    own panels are pushing charge into the bank (dots flow right→left, green);
//    negative means the inverter is drawing load (dots flow left→right, copper).
//
// Custom-drawn (no charting/animation NuGet) so it stays within the app's tiny
// dependency budget, matching SocRing / RadialGauge / MiniChart.
public sealed class EnergyFlowDiagram : FrameworkElement
{
    // ---- Inputs (all force a re-render) ----
    public static readonly DependencyProperty SolarWProperty      = DP(nameof(SolarW), 0.0);
    public static readonly DependencyProperty BatteryNetWProperty = DP(nameof(BatteryNetW), 0.0);
    public static readonly DependencyProperty EasunWProperty      = DP(nameof(EasunW), 0.0);
    public static readonly DependencyProperty SocProperty         = DP(nameof(Soc), 0.0);
    public static readonly DependencyProperty BatteryVoltageProperty = DP(nameof(BatteryVoltage), 0.0);
    public static readonly DependencyProperty HasInferenceProperty = DP(nameof(HasInference), false);
    public static readonly DependencyProperty IsLiveProperty       = DP(nameof(IsLive), false);

    public double SolarW         { get => (double)GetValue(SolarWProperty); set => SetValue(SolarWProperty, value); }
    public double BatteryNetW    { get => (double)GetValue(BatteryNetWProperty); set => SetValue(BatteryNetWProperty, value); }
    public double EasunW         { get => (double)GetValue(EasunWProperty); set => SetValue(EasunWProperty, value); }
    public double Soc            { get => (double)GetValue(SocProperty); set => SetValue(SocProperty, value); }
    public double BatteryVoltage { get => (double)GetValue(BatteryVoltageProperty); set => SetValue(BatteryVoltageProperty, value); }
    public bool   HasInference   { get => (bool)GetValue(HasInferenceProperty); set => SetValue(HasInferenceProperty, value); }
    public bool   IsLive         { get => (bool)GetValue(IsLiveProperty); set => SetValue(IsLiveProperty, value); }

    private static DependencyProperty DP(string name, object def) =>
        DependencyProperty.Register(name, def.GetType(), typeof(EnergyFlowDiagram),
            new FrameworkPropertyMetadata(def, FrameworkPropertyMetadataOptions.AffectsRender));

    // ---- Palette (mirrors Ui/Theme/Colors.xaml) ----
    private const uint TealC   = 0xFF0F766E;  // solar
    private const uint BlueC   = 0xFF1D4ED8;  // battery
    private const uint GreenC  = 0xFF16A34A;  // charge / ok
    private const uint CopperC = 0xFFB45309;  // load draw
    private const uint InkHi   = 0xFF0F172A;
    private const uint InkLo   = 0xFF6B7280;
    private const uint Line    = 0xFFE2E5EE;
    private const uint Track    = 0xFFE8EBF2;

    private readonly DispatcherTimer _timer;
    private double _phase;

    public EnergyFlowDiagram()
    {
        _timer = new DispatcherTimer(DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(40) };
        _timer.Tick += (_, _) =>
        {
            // Only spend frames when something is actually flowing.
            if (Math.Abs(SolarW) + Math.Abs(EasunW) < 0.5) return;
            _phase += 0.016;
            if (_phase > 1e6) _phase = 0;
            InvalidateVisual();
        };
        Loaded   += (_, _) => _timer.Start();
        Unloaded += (_, _) => _timer.Stop();
        IsVisibleChanged += (_, e) => { if ((bool)e.NewValue) _timer.Start(); else _timer.Stop(); };
    }

    protected override Size MeasureOverride(Size availableSize)
    {
        double w = double.IsInfinity(availableSize.Width)  ? 640 : availableSize.Width;
        double h = double.IsInfinity(availableSize.Height) ? 220 : availableSize.Height;
        return new Size(w, h);
    }

    protected override void OnRender(DrawingContext drawingContext)
    {
        var dc = drawingContext;
        double w = ActualWidth, h = ActualHeight;
        if (w <= 0 || h <= 0) return;

        const double pad = 8;
        double aw = w - pad * 2;
        double sideW = Math.Max(120, aw * 0.255);
        double midW  = Math.Max(150, aw * 0.30);
        double conn  = Math.Max(48, (aw - sideW * 2 - midW) / 2);

        double midY  = h / 2;
        double sideH = Math.Min(h - 8, 104);
        double centH = Math.Min(h - 4, 132);

        double solarL = pad;
        double battL  = solarL + sideW + conn;
        double easunL = battL + midW + conn;

        var solar = new Rect(solarL, midY - sideH / 2, sideW, sideH);
        var batt  = new Rect(battL,  midY - centH / 2, midW,  centH);
        var easun = new Rect(easunL, midY - sideH / 2, sideW, sideH);

        double scale = Math.Max(50, Math.Max(SolarW, Math.Max(Math.Abs(EasunW), Math.Abs(BatteryNetW))));

        // ---- Connectors (drawn under the nodes so the rounded cards cap them) ----
        // Solar → Battery: always charging direction (left → right).
        DrawConnector(dc, solar.Right, batt.Left, midY, SolarW, scale, Brush(TealC), travelPositive: true, faded: !IsLive);

        // Battery ↔ EA SUN: direction depends on the sign of the inferred flow.
        bool easunCharges = EasunW >= 0;
        uint easunCol = easunCharges ? GreenC : CopperC;
        // x0→x1 is the travel direction of the dots.
        double e0 = easunCharges ? easun.Left : batt.Right;
        double e1 = easunCharges ? batt.Right : easun.Left;
        DrawConnector(dc, e0, e1, midY, Math.Abs(EasunW), scale, Brush(easunCol),
                      travelPositive: e1 >= e0, faded: !HasInference);

        // ---- Nodes ----
        DrawSolarNode(dc, solar, !IsLive);
        DrawBatteryNode(dc, batt, !IsLive);
        DrawEasunNode(dc, easun, easunCol, easunCharges, !HasInference);
    }

    // ==================================================================
    //  Connectors
    // ==================================================================

    private void DrawConnector(DrawingContext dc, double x0, double x1, double y,
                               double magnitude, double scale, Brush color, bool travelPositive, bool faded)
    {
        double left = Math.Min(x0, x1), right = Math.Max(x0, x1);
        if (right - left < 2) return;

        // Faint base rail.
        var rail = new Pen(Brush(Track), 4) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round };
        dc.DrawLine(rail, new Point(left, y), new Point(right, y));

        if (faded || magnitude < 0.5) return;

        double f = Math.Clamp(magnitude / scale, 0, 1);
        double thick = 4 + 10 * f;

        // Translucent coloured band under the dots.
        var bandCol = ((SolidColorBrush)color).Color;
        var band = new Pen(new SolidColorBrush(Color.FromArgb(0x3A, bandCol.R, bandCol.G, bandCol.B)), thick)
        {
            StartLineCap = PenLineCap.Round,
            EndLineCap   = PenLineCap.Round,
        };
        dc.DrawLine(band, new Point(left, y), new Point(right, y));

        // Marching dots — the sense of flow. Higher power ⇒ faster, denser.
        double len = right - left;
        int n = Math.Max(3, (int)(len / 22));
        double speed = 0.45 + 0.9 * f;
        double dotR = 1.8 + 2.2 * f;
        var dotBrush = (SolidColorBrush)color;
        for (int i = 0; i <= n; i++)
        {
            double prog = Frac(i / (double)n + _phase * speed);
            double t = travelPositive ? prog : 1 - prog;     // 0..1 along x0→x1
            double x = left + t * len;
            double a = Math.Sin(Math.PI * prog);             // fade in/out at the ends
            var c = new SolidColorBrush(Color.FromArgb((byte)(220 * a), dotBrush.Color.R, dotBrush.Color.G, dotBrush.Color.B));
            c.Freeze();
            dc.DrawEllipse(c, null, new Point(x, y), dotR, dotR);
        }

        // Arrowhead at the destination end, pointing the way the dots travel.
        double tipX = travelPositive ? right : left;
        int dir = travelPositive ? 1 : -1;
        double ah = 4 + 2 * f;
        var head = new StreamGeometry();
        using (var g = head.Open())
        {
            g.BeginFigure(new Point(tipX, y), true, true);
            g.LineTo(new Point(tipX - dir * ah * 1.6, y - ah), true, false);
            g.LineTo(new Point(tipX - dir * ah * 1.6, y + ah), true, false);
        }
        head.Freeze();
        dc.DrawGeometry(dotBrush, null, head);
    }

    // ==================================================================
    //  Nodes
    // ==================================================================

    private void DrawSolarNode(DrawingContext dc, Rect r, bool faded)
    {
        DrawCard(dc, r, TealC, faded);
        double cx = r.Left + r.Width / 2;
        DrawSun(dc, new Point(r.Left + 20, r.Top + 20), 9, Brush(faded ? InkLo : TealC));
        DrawText(dc, "MPPT ARRAY", 10.5, faded ? InkLo : TealC, FontWeights.SemiBold,
                 new Point(r.Left + 36, r.Top + 12));
        CenterText(dc, $"{SolarW:0} W", 27, faded ? InkLo : InkHi, FontWeights.SemiBold, cx, r.Top + r.Height * 0.52);
        CenterText(dc, "sensed PV", 11, InkLo, FontWeights.Normal, cx, r.Bottom - 22);
    }

    private void DrawEasunNode(DrawingContext dc, Rect r, uint accent, bool charging, bool faded)
    {
        DrawCard(dc, r, faded ? (uint)InkLo : accent, faded);
        double cx = r.Left + r.Width / 2;
        DrawBolt(dc, new Point(r.Left + 18, r.Top + 19), 13, Brush(faded ? InkLo : accent));
        DrawText(dc, "EA SUN", 10.5, faded ? InkLo : accent, FontWeights.SemiBold, new Point(r.Left + 34, r.Top + 12));

        if (faded)
        {
            CenterText(dc, "—", 27, InkLo, FontWeights.SemiBold, cx, r.Top + r.Height * 0.52);
            CenterText(dc, "add battery profile", 10.5, InkLo, FontWeights.Normal, cx, r.Bottom - 22);
            return;
        }
        CenterText(dc, $"{Math.Abs(EasunW):0} W", 27, InkHi, FontWeights.SemiBold, cx, r.Top + r.Height * 0.52);
        CenterText(dc, charging ? "array charging" : "inverter load", 11, InkLo, FontWeights.Normal, cx, r.Bottom - 22);
    }

    private void DrawBatteryNode(DrawingContext dc, Rect r, bool faded)
    {
        DrawCard(dc, r, BlueC, faded, emphasised: true);
        double cx = r.Left + r.Width / 2;
        DrawText(dc, "BATTERY BANK", 10.5, faded ? InkLo : BlueC, FontWeights.SemiBold, new Point(r.Left + 14, r.Top + 12));

        // Horizontal battery glyph, filled to SoC with the red→amber→green ramp.
        double soc = Math.Clamp(double.IsNaN(Soc) ? 0 : Soc, 0, 100);
        double bw = Math.Min(r.Width - 40, 120), bh = 26;
        double bx = cx - bw / 2, by = r.Top + 34;
        var ramp = faded ? Brush(InkLo) : Brush(RampColor(soc));

        // Body + terminal nub.
        var bodyPen = new Pen(Brush(faded ? InkLo : 0xFFB9C0CE), 1.5);
        dc.DrawRoundedRectangle(Brush(0xFFFFFFFF), bodyPen, new Rect(bx, by, bw, bh), 4, 4);
        dc.DrawRoundedRectangle(Brush(faded ? InkLo : 0xFFB9C0CE), null, new Rect(bx + bw, by + bh * 0.3, 4, bh * 0.4), 1, 1);
        // Fill.
        double fillW = Math.Max(0, (bw - 6) * soc / 100.0);
        if (fillW > 1)
            dc.DrawRoundedRectangle(ramp, null, new Rect(bx + 3, by + 3, fillW, bh - 6), 2, 2);
        CenterText(dc, $"{soc:0}%", 13, soc > 48 ? 0xFFFFFFFF : InkHi, FontWeights.SemiBold, bx + 3 + Math.Max(14, fillW) / 2, by + bh / 2);

        // Volts + signed net power.
        CenterText(dc, $"{BatteryVoltage:0.0} V", 15, faded ? InkLo : InkHi, FontWeights.SemiBold, cx, by + bh + 18);
        double net = BatteryNetW;
        uint netCol = faded ? InkLo : net >= 0 ? GreenC : CopperC;
        string netStr = $"net {net:+0;-0;0} W";
        CenterText(dc, netStr, 11.5, netCol, FontWeights.SemiBold, cx, r.Bottom - 18);
    }

    private static void DrawCard(DrawingContext dc, Rect r, uint accent, bool faded, bool emphasised = false)
    {
        var ac = ColorOf(accent);
        var fill = new SolidColorBrush(Color.FromArgb(faded ? (byte)0x06 : (byte)0x0E, ac.R, ac.G, ac.B)); fill.Freeze();
        var pen = new Pen(new SolidColorBrush(Color.FromArgb(emphasised && !faded ? (byte)0x80 : (byte)0x40, ac.R, ac.G, ac.B)),
                          emphasised ? 1.5 : 1);
        dc.DrawRoundedRectangle(fill, pen, r, 10, 10);
    }

    // ==================================================================
    //  Vector glyphs
    // ==================================================================

    private static void DrawSun(DrawingContext dc, Point c, double r, Brush b)
    {
        dc.DrawEllipse(b, null, c, r * 0.62, r * 0.62);
        var pen = new Pen(b, 1.6) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round };
        for (int i = 0; i < 8; i++)
        {
            double a = i * Math.PI / 4;
            var p0 = new Point(c.X + Math.Cos(a) * r * 0.95, c.Y + Math.Sin(a) * r * 0.95);
            var p1 = new Point(c.X + Math.Cos(a) * r * 1.35, c.Y + Math.Sin(a) * r * 1.35);
            dc.DrawLine(pen, p0, p1);
        }
    }

    private static void DrawBolt(DrawingContext dc, Point c, double h, Brush b)
    {
        double w = h * 0.6;
        var g = new StreamGeometry();
        using (var ctx = g.Open())
        {
            ctx.BeginFigure(new Point(c.X + w * 0.10, c.Y - h / 2), true, true);
            ctx.LineTo(new Point(c.X - w * 0.45, c.Y + h * 0.10), true, false);
            ctx.LineTo(new Point(c.X + w * 0.02, c.Y + h * 0.10), true, false);
            ctx.LineTo(new Point(c.X - w * 0.10, c.Y + h / 2), true, false);
            ctx.LineTo(new Point(c.X + w * 0.50, c.Y - h * 0.12), true, false);
            ctx.LineTo(new Point(c.X + w * 0.02, c.Y - h * 0.12), true, false);
        }
        g.Freeze();
        dc.DrawGeometry(b, null, g);
    }

    // ==================================================================
    //  Text + colour helpers
    // ==================================================================

    private static void DrawText(DrawingContext dc, string s, double size, uint argb, FontWeight w, Point at)
        => dc.DrawText(Fmt(s, size, argb, w), at);

    private static void CenterText(DrawingContext dc, string s, double size, uint argb, FontWeight w, double cx, double cy)
    {
        var t = Fmt(s, size, argb, w);
        dc.DrawText(t, new Point(cx - t.Width / 2, cy - t.Height / 2));
    }

    private static FormattedText Fmt(string s, double size, uint argb, FontWeight w) => new(
        s, CultureInfo.CurrentCulture, FlowDirection.LeftToRight,
        new Typeface(new FontFamily("Segoe UI"), FontStyles.Normal, w, FontStretches.Normal),
        size, Brush(argb), 1.0);

    private static double Frac(double v) => v - Math.Floor(v);

    private static Color RampColor(double pct) => pct switch
    {
        < 25 => Color.FromRgb(0xC8, 0x10, 0x2E),
        < 60 => Color.FromRgb(0xD9, 0x77, 0x06),
        _    => Color.FromRgb(0x16, 0xA3, 0x4A),
    };

    private static Color ColorOf(uint argb) => Color.FromArgb(
        (byte)((argb >> 24) & 0xFF), (byte)((argb >> 16) & 0xFF), (byte)((argb >> 8) & 0xFF), (byte)(argb & 0xFF));

    private static SolidColorBrush Brush(uint argb) => Brush(ColorOf(argb));

    private static SolidColorBrush Brush(Color c)
    {
        var b = new SolidColorBrush(c);
        b.Freeze();
        return b;
    }
}
