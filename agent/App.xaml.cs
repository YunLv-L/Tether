using System.Windows;
using MaterialDesignThemes.Wpf;

namespace TetherAgent;

public partial class App : Application
{
    private void Application_Startup(object sender, StartupEventArgs e)
    {
        // 默认深色主题
        var paletteHelper = new PaletteHelper();
        var theme = paletteHelper.GetTheme();
        theme.SetBaseTheme(Theme.Dark);
        paletteHelper.SetTheme(theme);
    }
}