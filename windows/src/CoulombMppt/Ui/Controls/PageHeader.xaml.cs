using System.Windows;
using System.Windows.Controls;

namespace CoulombMppt.Ui.Controls;

public partial class PageHeader : UserControl
{
    public PageHeader() => InitializeComponent();

    public static readonly DependencyProperty TitleProperty =
        DependencyProperty.Register(nameof(Title), typeof(string), typeof(PageHeader),
            new PropertyMetadata(""));
    public static readonly DependencyProperty SubtitleProperty =
        DependencyProperty.Register(nameof(Subtitle), typeof(string), typeof(PageHeader),
            new PropertyMetadata("", (d, e) =>
                ((PageHeader)d).HasSubtitle = !string.IsNullOrEmpty(e.NewValue as string)));
    public static readonly DependencyProperty HasSubtitleProperty =
        DependencyProperty.Register(nameof(HasSubtitle), typeof(bool), typeof(PageHeader),
            new PropertyMetadata(false));
    public static readonly DependencyProperty ActionContentProperty =
        DependencyProperty.Register(nameof(ActionContent), typeof(object), typeof(PageHeader),
            new PropertyMetadata(null));

    public string Title    { get => (string)GetValue(TitleProperty);    set => SetValue(TitleProperty, value); }
    public string Subtitle { get => (string)GetValue(SubtitleProperty); set => SetValue(SubtitleProperty, value); }
    public bool   HasSubtitle { get => (bool)GetValue(HasSubtitleProperty); set => SetValue(HasSubtitleProperty, value); }
    public object? ActionContent
    {
        get => GetValue(ActionContentProperty);
        set => SetValue(ActionContentProperty, value);
    }
}
