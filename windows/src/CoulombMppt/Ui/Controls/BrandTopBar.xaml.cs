using System.Windows;
using System.Windows.Controls;
using CoulombMppt.Services;

namespace CoulombMppt.Ui.Controls;

public partial class BrandTopBar : UserControl
{
    public BrandTopBar() => InitializeComponent();

    public static readonly DependencyProperty TitleProperty =
        DependencyProperty.Register(nameof(Title), typeof(string), typeof(BrandTopBar),
            new PropertyMetadata(""));

    public static readonly DependencyProperty SubtitleProperty =
        DependencyProperty.Register(nameof(Subtitle), typeof(string), typeof(BrandTopBar),
            new PropertyMetadata(""));

    public static readonly DependencyProperty ActionContentProperty =
        DependencyProperty.Register(nameof(ActionContent), typeof(object), typeof(BrandTopBar),
            new PropertyMetadata(null));

    public static readonly DependencyProperty ShowBackProperty =
        DependencyProperty.Register(nameof(ShowBack), typeof(bool), typeof(BrandTopBar),
            new PropertyMetadata(false));

    public string Title    { get => (string)GetValue(TitleProperty);    set => SetValue(TitleProperty, value); }
    public string Subtitle { get => (string)GetValue(SubtitleProperty); set => SetValue(SubtitleProperty, value); }
    public object? ActionContent
    {
        get => GetValue(ActionContentProperty);
        set => SetValue(ActionContentProperty, value);
    }
    public bool ShowBack { get => (bool)GetValue(ShowBackProperty); set => SetValue(ShowBackProperty, value); }

    private void OnBack(object sender, RoutedEventArgs e) => Navigation.Pop();
}
