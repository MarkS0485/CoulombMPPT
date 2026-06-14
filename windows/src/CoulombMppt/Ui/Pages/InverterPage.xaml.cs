using System.ComponentModel;
using System.Globalization;
using System.Windows.Controls;
using OxyPlot;
using OxyPlot.Axes;
using OxyPlot.Legends;
using OxyPlot.Series;
using CoulombMppt.Ui.ViewModels;

namespace CoulombMppt.Ui.Pages;

public partial class InverterPage : UserControl
{
    private InverterViewModel? _vm;

    public InverterPage()
    {
        InitializeComponent();
        Loaded += (_, _) =>
        {
            _vm = DataContext as InverterViewModel;
            if (_vm != null)
            {
                _vm.PropertyChanged += OnVmPropertyChanged;
                RebuildBarChart();
            }
        };
        Unloaded += (_, _) =>
        {
            if (_vm != null) _vm.PropertyChanged -= OnVmPropertyChanged;
        };
    }

    private void OnVmPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(InverterViewModel.WeekEasunCharge)
                           or nameof(InverterViewModel.WeekEasunLoad)
                           or nameof(InverterViewModel.WeekDayLabels))
        {
            RebuildBarChart();
        }
    }

    private void RebuildBarChart()
    {
        if (_vm == null || WeekChart == null) return;

        var charge = _vm.WeekEasunCharge;
        var load   = _vm.WeekEasunLoad;
        var labels = _vm.WeekDayLabels;

        int n = Math.Min(Math.Min(charge.Count, load.Count), labels.Count);
        if (n == 0)
        {
            WeekChart.BarModel = null;
            return;
        }

        var model = new PlotModel
        {
            IsLegendVisible         = true,
            Background              = OxyColors.Transparent,
            PlotAreaBackground      = OxyColors.Transparent,
            PlotAreaBorderThickness = new OxyThickness(0),
            Padding                 = new OxyThickness(4, 4, 4, 4),
        };

        // OxyPlot 2.x: Legend is a separate object added to Legends collection.
        model.Legends.Add(new Legend
        {
            LegendPosition  = LegendPosition.TopRight,
            LegendFontSize  = 9.5,
            LegendTextColor = OxyColor.FromArgb(0xCC, 0x6B, 0x72, 0x80),
        });

        // CategoryAxis on bottom for day labels.
        var catAxis = new CategoryAxis
        {
            Position      = AxisPosition.Bottom,
            AxislineColor = OxyColor.FromArgb(0x40, 0xE2, 0xE5, 0xEE),
            TicklineColor = OxyColor.FromArgb(0x40, 0xE2, 0xE5, 0xEE),
            TextColor     = OxyColor.FromArgb(0xCC, 0x6B, 0x72, 0x80),
            FontSize      = 9.5,
            GapWidth      = 0.4,
        };
        for (int i = 0; i < n; i++)
            catAxis.Labels.Add(labels[i]);

        var valAxis = new LinearAxis
        {
            Position      = AxisPosition.Left,
            Minimum       = 0,
            AxislineColor = OxyColor.FromArgb(0x40, 0xE2, 0xE5, 0xEE),
            TicklineColor = OxyColor.FromArgb(0x40, 0xE2, 0xE5, 0xEE),
            TextColor     = OxyColor.FromArgb(0xCC, 0x6B, 0x72, 0x80),
            FontSize      = 9.5,
            StringFormat  = "0",
            Title         = "Wh",
            TitleFontSize = 9.5,
            TitleColor    = OxyColor.FromArgb(0xCC, 0x6B, 0x72, 0x80),
        };

        model.Axes.Add(catAxis);
        model.Axes.Add(valAxis);

        // Two LinearBarSeries side-by-side: charge (green) and load (copper).
        // LinearBarSeries uses numeric X for position matching CategoryAxis indices.
        var chargeSeries = new LinearBarSeries
        {
            Title              = "Charge (Wh)",
            FillColor          = OxyColor.FromRgb(0x16, 0xA3, 0x4A),
            StrokeColor        = OxyColors.Transparent,
            StrokeThickness    = 0,
            BarWidth           = 0.35,
            TrackerFormatString = "{0}\n{1}: {2:0} Wh",
        };

        var loadSeries = new LinearBarSeries
        {
            Title              = "Load (Wh)",
            FillColor          = OxyColor.FromRgb(0xB4, 0x53, 0x09),
            StrokeColor        = OxyColors.Transparent,
            StrokeThickness    = 0,
            BarWidth           = 0.35,
            TrackerFormatString = "{0}\n{1}: {2:0} Wh",
        };

        for (int i = 0; i < n; i++)
        {
            chargeSeries.Points.Add(new DataPoint(i - 0.18, charge[i]));
            loadSeries.Points.Add(new DataPoint(i + 0.18, load[i]));
        }

        model.Series.Add(chargeSeries);
        model.Series.Add(loadSeries);

        WeekChart.BarModel = model;
    }
}
