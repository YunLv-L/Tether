using System.Windows;
using System.Windows.Input;
using MaterialDesignThemes.Wpf;

namespace TetherAgent;

public partial class MainWindow : Window
{
    // 临时占位：IdentityService 将在后续实现
    // private IdentityService? _identityService;
    private object? _identityServicePlaceholder;

    public MainWindow()
    {
        InitializeComponent();
        Loaded += MainWindow_Loaded;
    }

    private void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        // 临时注释：等待 IdentityService 实现
        /*
        _identityService = new IdentityService();
        _identityService.OnDeviceFound += (device) =>
        {
            Dispatcher.Invoke(() =>
            {
                DeviceList.Items.Add(device);
                OnlineCountText.Text = $"{DeviceList.Items.Count} 台在线";
            });
        };
        _identityService.Start();
        */

        DeviceNameText.Text = Environment.MachineName;
        IpText.Text = GetLocalIP();
    }

    private string GetLocalIP()
    {
        try
        {
            var host = System.Net.Dns.GetHostEntry(System.Net.Dns.GetHostName());
            foreach (var ip in host.AddressList)
                if (ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork &&
                    !System.Net.IPAddress.IsLoopback(ip))
                    return ip.ToString();
        }
        catch { }
        return "127.0.0.1";
    }

    private void MinimizeButton_Click(object sender, RoutedEventArgs e)
        => WindowState = WindowState.Minimized;

    private void MaximizeButton_Click(object sender, RoutedEventArgs e)
        => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void CloseButton_Click(object sender, RoutedEventArgs e)
        => Close();

    private void ScanButton_Click(object sender, RoutedEventArgs e)
    {
        // _identityService?.BroadcastDiscovery();
    }

    private void LockButton_Click(object sender, RoutedEventArgs e)
    {
        // _identityService?.SendCommand("lock");
    }

    private void SleepButton_Click(object sender, RoutedEventArgs e)
    {
        // _identityService?.SendCommand("sleep");
    }

    private void ShutdownButton_Click(object sender, RoutedEventArgs e)
    {
        // _identityService?.SendCommand("shutdown");
    }

    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed)
            DragMove();
    }
}