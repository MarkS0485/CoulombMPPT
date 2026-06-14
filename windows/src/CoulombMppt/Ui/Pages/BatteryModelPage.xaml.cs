using System.ComponentModel;
using System.Windows.Controls;
using OxyPlot;
using OxyPlot.Axes;
using OxyPlot.Legends;
using OxyPlot.Series;
using CoulombMppt.Data;
using CoulombMppt.Ui.ViewModels;

namespace CoulombMppt.Ui.Pages;

// Read-only Battery Model / Calibration page. The OCV-vs-SoC chart is a full
// PlotModel handed to OxyChartControl via BarModel (the scatter/line mode), built
// here in code-behind exactly like InverterPage's 7-day bar chart.
public partial class BatteryModelPage : UserControl
{
    private BatteryModelViewModel? _vm;

    public BatteryModelPage()
    {
        InitializeComponent();
        Loaded += (_, _) =>
        {
            _vm = DataContext as BatteryModelViewModel;
            if (_vm != null)
            {
                _vm.PropertyChanged += OnVmPropertyChanged;
                RebuildOcvChart();
            }
        };
        Unloaded += (_, _) =>
        {
            if (_vm != null) _vm.PropertyChanged -= OnVmPropertyChanged;
        };
    }

    private void OnVmPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(BatteryModelViewModel.OcvPoints)
                           or nameof(BatteryModelViewModel.LinearEmptyV)
                           or nameof(BatteryModelViewModel.LinearFullV))
        {
            RebuildOcvChart();
        }
    }

    private void RebuildOcvChart()
    {
        if (_vm == null || OcvChart == null) return;

        var points = _vm.OcvPoints;

        var model = new PlotModel
        {
            IsLegendVisible         = true,
            Background              = OxyColors.Transparent,
            PlotAreaBackground      = OxyColors.Transparent,
            PlotAreaBorderThickness = new OxyThickness(0),
            Padding                 = new OxyThickness(4, 4, 4, 4),
        };

        model.Legends.Add(new Legend
        {
            LegendPosition  = LegendPosition.BottomRight,
            LegendFontSize  = 9.5,
            LegendTextColor = OxyColor.FromArgb(0xCC, 0x6B, 0x72, 0x80),
        });

        var xAxis = new LinearAxis
        {
            Position      = AxisPosition.Bottom,
            Minimum       = 0,
            Maximum       = 100,
            Title         = "State of charge (%)",
            TitleFontSize = 9.5,
            TitleColor    = OxyColor.FromArgb(0xCC, 0x6B, 0x72, 0x80),
            AxislineColor = OxyColor.FromArgb(0x40, 0xE2, 0xE5, 0xEE),
            TicklineColor = OxyColor.FromArgb(0x40, 0xE2, 0xE5, 0xEE),
            TextColor     = OxyColor.FromArgb(0xCC, 0x6B, 0x72, 0x80),
            FontSize      = 9.5,
            MajorGridlineStyle = LineStyle.Solid,
            MajorGridlineColor = OxyColor.FromArgb(0x18, 0xE2, 0xE5, 0xEE),
        };

        var yAxis = new LinearAxis
        {
            Position      = AxisPosition.Left,
            Title         = "Rested OCV (V)",
            TitleFontSize = 9.5,
            TitleColor    = OxyColor.FromArgb(0xCC, 0x6B, 0x72, 0x80),
            AxislineColor = OxyColor.FromArgb(0x40, 0xE2, 0xE5, 0xEE),
            TicklineColor = OxyColor.FromArgb(0x40, 0xE2, 0xE5, 0xEE),
            TextColor     = OxyColor.FromArgb(0xCC, 0x6B, 0x72, 0x80),
            FontSize      = 9.5,
            StringFormat  = "0.0",
            MajorGridlineStyle = LineStyle.Solid,
            MajorGridlineColor = OxyColor.FromArgb(0x18, 0xE2, 0xE5, 0xEE),
        };

        model.Axes.Add(xAxis);
        model.Axes.Add(yAxis);

        // Old linear reference line: (0, EmptyV) → (100, FullV). Drawn first so it
        // sits behind the learned curve. This is "what we used to assume".
        if (_vm.LinearEmptyV is { } emptyV && _vm.LinearFullV is { } fullV)
        {
            var refLine = new LineSeries
            {
                Title           = "Old linear assumption",
                Color           = OxyColor.FromRgb(0x6B, 0x72, 0x80),
                StrokeThickness = 1.6,
                LineStyle       = LineStyle.Dash,
            };
            refLine.Points.Add(new DataPoint(0, emptyV));
            refLine.Points.Add(new DataPoint(100, fullV));
            model.Series.Add(refLine);
        }

        if (points.Count > 0)
        {
            // Learned piecewise curve (points are sorted ascending by SocPct).
            var curve = new LineSeries
            {
                Title           = "Learned curve",
                Color           = OxyColor.FromRgb(0x0F, 0x76, 0x6E),
                StrokeThickness = 2.0,
            };
            foreach (var p in points)
                curve.Points.Add(new DataPoint(p.SocPct, p.Ocv));
            model.Series.Add(curve);

            // Learned anchors as a scatter, marker size scaled by sample count.
            var scatter = new ScatterSeries
            {
                Title             = "Learned anchors",
                MarkerType        = MarkerType.Circle,
                MarkerFill        = OxyColor.FromRgb(0x0F, 0x76, 0x6E),
                MarkerStroke      = OxyColors.White,
                MarkerStrokeThickness = 1.0,
                TrackerFormatString = "{1}: {2:0.#} %\n{3}: {4:0.000} V",
            };
            int maxSamples = points.Max(p => p.SampleCount);
            foreach (var p in points)
            {
                double size = ScaleMarker(p.SampleCount, maxSamples);
                scatter.Points.Add(new ScatterPoint(p.SocPct, p.Ocv, size));
            }
            model.Series.Add(scatter);
        }

        OcvChart.BarModel = model;
    }

    // Marker radius scaled by sample count (3–8 px), guarding the single-point case.
    private static double ScaleMarker(int samples, int maxSamples)
    {
        if (maxSamples <= 0) return 4.0;
        double frac = Math.Clamp(samples / (double)maxSamples, 0.0, 1.0);
        return 3.0 + frac * 5.0;
    }
}
