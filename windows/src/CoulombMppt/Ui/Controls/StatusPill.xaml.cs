using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace CoulombMppt.Ui.Controls;

public enum StatusKind { Online, Offline, Stale, Warning }

public partial class StatusPill : UserControl
{
    public StatusPill()
    {
        InitializeComponent();
        ApplyKind();
    }

    public static readonly DependencyProperty LabelProperty =
        DependencyProperty.Register(nameof(Label), typeof(string), typeof(StatusPill),
            new PropertyMetadata(""));

    public static readonly DependencyProperty KindProperty =
        DependencyProperty.Register(nameof(Kind), typeof(StatusKind), typeof(StatusPill),
            new PropertyMetadata(StatusKind.Offline, (d, _) => ((StatusPill)d).ApplyKind()));

    public string Label
    {
        get => (string)GetValue(LabelProperty);
        set => SetValue(LabelProperty, value);
    }
    public StatusKind Kind
    {
        get => (StatusKind)GetValue(KindProperty);
        set => SetValue(KindProperty, value);
    }

    private void ApplyKind()
    {
        (Brush bg, Brush fg) = Kind switch
        {
            StatusKind.Online  => (Brush(0xFFE7F8EE), Brush(0xFF166534)),
            StatusKind.Stale   => (Brush(0xFFFFF7E5), Brush(0xFF92400E)),
            StatusKind.Warning => (Brush(0xFFFFEBE0), Brush(0xFF9A3412)),
            _                  => (Brush(0xFFF3F4F6), Brush(0xFF374151)),
        };
        if (bg is SolidColorBrush sbg) this.bg.Background = sbg;
        if (fg is SolidColorBrush sfg) this.dot.Fill = sfg;
        this.lbl.Foreground = fg;
    }

    private static SolidColorBrush Brush(uint argb)
    {
        var b = new SolidColorBrush(Color.FromArgb(
            (byte)((argb >> 24) & 0xFF),
            (byte)((argb >> 16) & 0xFF),
            (byte)((argb >> 8)  & 0xFF),
            (byte)( argb        & 0xFF)));
        b.Freeze();
        return b;
    }
}
