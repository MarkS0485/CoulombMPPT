using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using CoulombMppt.Ui.Controls;

namespace CoulombMppt.Ui;

public sealed class BoolToVisibilityConverter : IValueConverter
{
    public bool Inverted { get; set; }
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        bool b = value is bool x && x;
        if (Inverted) b = !b;
        return b ? Visibility.Visible : Visibility.Collapsed;
    }
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

// Visibility = Visible when the integer count is zero; collapsed otherwise.
// Swaps an empty-state hint in for an empty ItemsControl.
public sealed class CountZeroToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
        => (value is int n && n == 0) ? Visibility.Visible : Visibility.Collapsed;
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

// Visibility = Collapsed when the value is null or DBNull; Visible otherwise.
// Used to conditionally show UI elements that depend on a nullable value.
public sealed class NullToCollapsedConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
        => (value == null || value == DBNull.Value) ? Visibility.Collapsed : Visibility.Visible;
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

// Visibility = Visible when the integer count is greater than zero.
public sealed class CountPositiveToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
        => (value is int n && n > 0) ? Visibility.Visible : Visibility.Collapsed;
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

// Visible when the bound string is non-empty.
public sealed class StringNotEmptyToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
        => string.IsNullOrEmpty(value as string) ? Visibility.Collapsed : Visibility.Visible;
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

// Maps the connection-status label to the StatusPill colour kind.
public sealed class StatusLabelToKindConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var s = value as string ?? "";
        return s switch
        {
            "Connected"     => StatusKind.Online,
            "Relay (phone)" => StatusKind.Online,
            "Connecting…"   => StatusKind.Stale,
            "Discovering…"  => StatusKind.Stale,
            "Reconnecting…" => StatusKind.Stale,
            "Scanning…"     => StatusKind.Stale,
            "Failed"        => StatusKind.Warning,
            _               => StatusKind.Offline,
        };
    }
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

// Alert severity string ("CRIT" / "WARN") → banner/text brush.
public sealed class SeverityToBrushConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var key = (value as string) switch
        {
            "CRIT" => "ErrRed",
            "WARN" => "WarnAmber",
            _      => "InkLo",
        };
        return Application.Current.TryFindResource(key) ?? Brushes.Gray;
    }
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

// Tx/Rx direction → brush (Diagnostics frame log). True = PC → controller.
public sealed class TxToBrushConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var key = (value is bool b && b) ? "CoolBlue" : "SolarTeal";
        return Application.Current.TryFindResource(key) ?? Brushes.Gray;
    }
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

// Unix-ms timestamp → local "HH:mm:ss" string.
public sealed class UnixMsToTimeConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (value is long ms && ms > 0)
            return DateTimeOffset.FromUnixTimeMilliseconds(ms).ToLocalTime().ToString("HH:mm:ss", CultureInfo.InvariantCulture);
        return "";
    }
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}

// Unix-ms timestamp → local "yyyy-MM-dd HH:mm:ss" string.
public sealed class UnixMsToDateTimeConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (value is long ms && ms > 0)
            return DateTimeOffset.FromUnixTimeMilliseconds(ms).ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture);
        return "";
    }
    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture)
        => throw new NotSupportedException();
}
