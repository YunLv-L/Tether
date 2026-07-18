using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32;
using System.Drawing.Imaging;

namespace TetherAgent;

public partial class MainForm : Form
{
    // ===== Windows API =====
    [DllImport("user32.dll")]
    private static extern bool LockWorkStation();

    [DllImport("powrprof.dll", SetLastError = true)]
    private static extern bool SetSuspendState(bool hibernate, bool forceCritical, bool disableWakeEvent);

    // ===== 配置 =====
    private const int UDP_PORT = 5555;
    private const int TCP_PORT = 5556;
    private const int SCREEN_PORT = 5557;
    private const int BROADCAST_DURATION = 10;

    private readonly string _deviceName = Environment.MachineName;
    private readonly string _ipAddress = GetLocalIP();
    private TcpListener? _tcpListener;
    private TcpListener? _screenListener;
    private bool _isRunning = true;
    private NotifyIcon? _trayIcon;
    private Thread? _udpThread;
    private Thread? _tcpThread;
    private Thread? _screenThread;

    public MainForm()
    {
        InitializeComponent();
        SetupTrayIcon();
        Load += MainForm_Load;
        FormClosing += MainForm_FormClosing;
        UpdateAutoStartStatus();
    }

    private void MainForm_Load(object? sender, EventArgs e)
    {
        lblDeviceName.Text = _deviceName;
        lblIP.Text = _ipAddress;
        StartServices();
        this.WindowState = FormWindowState.Minimized;
        this.ShowInTaskbar = false;
    }

    private void MainForm_FormClosing(object? sender, FormClosingEventArgs e)
    {
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            this.WindowState = FormWindowState.Minimized;
            this.ShowInTaskbar = false;
            return;
        }
        
        _isRunning = false;
        _tcpListener?.Stop();
        _screenListener?.Stop();
        
        if (_udpThread != null && _udpThread.IsAlive)
            _udpThread.Join(1000);
        if (_tcpThread != null && _tcpThread.IsAlive)
            _tcpThread.Join(1000);
        if (_screenThread != null && _screenThread.IsAlive)
            _screenThread.Join(1000);
        
        _trayIcon?.Dispose();
    }

    // ==================== 启动服务 ====================
    private void StartServices()
    {
        _udpThread = new Thread(UdpBroadcastLoop) { IsBackground = true };
        _udpThread.Start();

        _tcpThread = new Thread(StartTcpServer) { IsBackground = true };
        _tcpThread.Start();

        _screenThread = new Thread(ScreenStreamLoop) { IsBackground = true };
        _screenThread.Start();

        AppendLog("🚀 服务已启动");
        AppendLog($"📡 UDP 广播端口: {UDP_PORT}");
        AppendLog($"🔌 TCP 指令端口: {TCP_PORT}");
        AppendLog($"🖥️ 画面传输端口: {SCREEN_PORT}");
        AppendLog($"💻 设备: {_deviceName}");
        AppendLog($"📶 IP: {_ipAddress}");
    }

    // ==================== UDP 广播 ====================
    private void UdpBroadcastLoop()
    {
        try
        {
            using var udpClient = new UdpClient();
            udpClient.EnableBroadcast = true;
            var endpoint = new IPEndPoint(IPAddress.Broadcast, UDP_PORT);
            string message = $"TETHER_AGENT|{_deviceName}|{_ipAddress}|1.0";
            byte[] data = Encoding.UTF8.GetBytes(message);

            DateTime endTime = DateTime.Now.AddSeconds(BROADCAST_DURATION);
            int count = 0;

            while (DateTime.Now < endTime && _isRunning)
            {
                udpClient.Send(data, data.Length, endpoint);
                count++;
                AppendLog($"📡 广播 #{count}");
                Thread.Sleep(2000);
            }
            AppendLog("📡 广播结束 (TCP 服务持续运行)");
        }
        catch (Exception ex)
        {
            AppendLog($"❌ UDP 错误: {ex.Message}");
        }
    }

    // ==================== TCP 指令服务 ====================
    private void StartTcpServer()
    {
        try
        {
            _tcpListener = new TcpListener(IPAddress.Any, TCP_PORT);
            _tcpListener.Start();

            while (_isRunning)
            {
                var client = _tcpListener.AcceptTcpClient();
                ThreadPool.QueueUserWorkItem(HandleClient, client);
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ TCP 异常: {ex.Message}");
        }
    }

    private void HandleClient(object? state)
    {
        if (state is not TcpClient client) return;

        try
        {
            using (client)
            using (var stream = client.GetStream())
            {
                byte[] buffer = new byte[1024];
                int bytesRead = stream.Read(buffer, 0, buffer.Length);

                if (bytesRead > 0)
                {
                    string command = Encoding.UTF8.GetString(buffer, 0, bytesRead).Trim().ToLower();
                    AppendLog($"📨 指令: {command} ({client.Client.RemoteEndPoint})");
                    string result = ExecuteCommand(command);
                    AppendLog($"✅ 执行: {result}");

                    byte[] response = Encoding.UTF8.GetBytes($"{result}\n");
                    stream.Write(response, 0, response.Length);
                    stream.Flush();
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ 处理异常: {ex.Message}");
        }
    }

    private string ExecuteCommand(string command)
    {
        try
        {
            switch (command)
            {
                case "lock": return LockWorkStation() ? "已锁定" : "锁定失败";
                case "sleep": return SetSuspendState(false, true, false) ? "正在睡眠" : "睡眠失败";
                case "shutdown": Process.Start("shutdown", "/s /t 3"); return "正在关机 (3秒后)";
                case "ping": return "pong";
                default: return $"未知指令: {command}";
            }
        }
        catch (Exception ex)
        {
            return $"执行失败: {ex.Message}";
        }
    }

    // ==================== 画面传输（TCP 推流） ====================
    private void ScreenStreamLoop()
    {
        try
        {
            _screenListener = new TcpListener(IPAddress.Any, SCREEN_PORT);
            _screenListener.Start();

            while (_isRunning)
            {
                var client = _screenListener.AcceptTcpClient();
                ThreadPool.QueueUserWorkItem(HandleScreenClient, client);
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ 画面传输异常: {ex.Message}");
        }
    }

    private void HandleScreenClient(object? state)
    {
        if (state is not TcpClient client) return;

        try
        {
            using (client)
            using (var stream = client.GetStream())
            {
                client.ReceiveTimeout = 5000;
                client.SendTimeout = 5000;

                // 发送屏幕尺寸
                var bounds = Screen.PrimaryScreen.Bounds;
                var sizeInfo = $"{bounds.Width}|{bounds.Height}\n";
                byte[] sizeData = Encoding.UTF8.GetBytes(sizeInfo);
                stream.Write(sizeData, 0, sizeData.Length);
                stream.Flush();
                AppendLog($"🖥️ 画面客户端连接: {client.Client.RemoteEndPoint} ({bounds.Width}x{bounds.Height})");

                // 持续推流
                while (_isRunning && client.Connected)
                {
                    var imageData = CaptureScreen();
                    if (imageData == null) continue;

                    // 发送长度(4字节) + 图像数据
                    byte[] lengthBytes = BitConverter.GetBytes(imageData.Length);
                    stream.Write(lengthBytes, 0, 4);
                    stream.Write(imageData, 0, imageData.Length);
                    stream.Flush();

                    // 限制帧率 ~30fps
                    Thread.Sleep(33);
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ 画面传输断开: {ex.Message}");
        }
    }

    private byte[]? CaptureScreen()
    {
        try
        {
            var bounds = Screen.PrimaryScreen.Bounds;
            using (var bitmap = new Bitmap(bounds.Width, bounds.Height))
            using (var graphics = Graphics.FromImage(bitmap))
            {
                graphics.CopyFromScreen(bounds.X, bounds.Y, 0, 0, bounds.Size);
                using (var ms = new MemoryStream())
                {
                    var codec = ImageCodecInfo.GetImageEncoders()
                        .FirstOrDefault(c => c.FormatID == ImageFormat.Jpeg.Guid);
                    var encoderParams = new EncoderParameters(1);
                    encoderParams.Param[0] = new EncoderParameter(
                        System.Drawing.Imaging.Encoder.Quality,
                        85
                    );
                    bitmap.Save(ms, codec, encoderParams);
                    return ms.ToArray();
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ 截屏失败: {ex.Message}");
            return null;
        }
    }

    // ==================== 开机自启 ====================
    private const string REGISTRY_RUN_KEY = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run";
    private const string APP_NAME = "TetherAgent";

    private void UpdateAutoStartStatus()
    {
        // 在UI上显示状态（如果有CheckBox或Label的话）
    }

    private bool IsAutoStartEnabled()
    {
        try
        {
            using (var key = Registry.CurrentUser.OpenSubKey(REGISTRY_RUN_KEY))
            {
                return key?.GetValue(APP_NAME) != null;
            }
        }
        catch { return false; }
    }

    private void SetAutoStart(bool enable)
    {
        try
        {
            using (var key = Registry.CurrentUser.OpenSubKey(REGISTRY_RUN_KEY, true))
            {
                if (enable)
                {
                    var exePath = Application.ExecutablePath;
                    key?.SetValue(APP_NAME, $"\"{exePath}\"");
                    AppendLog("🔁 开机自启已启用");
                }
                else
                {
                    key?.DeleteValue(APP_NAME, false);
                    AppendLog("🔁 开机自启已关闭");
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ 开机自启设置失败: {ex.Message}");
        }
    }

    // ==================== 工具方法 ====================
    private static string GetLocalIP()
    {
        try
        {
            var host = Dns.GetHostEntry(Dns.GetHostName());
            foreach (var ip in host.AddressList)
                if (ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))
                    return ip.ToString();
        }
        catch { }
        return "127.0.0.1";
    }

    private void AppendLog(string message)
    {
        if (txtLog.InvokeRequired)
        {
            txtLog.Invoke(() => AppendLog(message));
            return;
        }
        string time = DateTime.Now.ToString("HH:mm:ss");
        txtLog.AppendText($"[{time}] {message}\n");
        txtLog.ScrollToCaret();
    }

    private void SetupTrayIcon()
    {
        _trayIcon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = $"Tether Agent\n{_deviceName}\n{_ipAddress}",
            Visible = true
        };

        ContextMenuStrip menu = new ContextMenuStrip();

        var autoStartItem = new ToolStripMenuItem("🔁 开机自启");
        autoStartItem.Checked = IsAutoStartEnabled();
        autoStartItem.Click += (s, e) =>
        {
            bool newState = !autoStartItem.Checked;
            SetAutoStart(newState);
            autoStartItem.Checked = newState;
        };
        menu.Items.Add(autoStartItem);

        menu.Items.Add("-");
        menu.Items.Add("🖥️ 显示主窗口", null, (s, e) =>
        {
            this.WindowState = FormWindowState.Normal;
            this.ShowInTaskbar = true;
            this.BringToFront();
        });
        menu.Items.Add("-");
        menu.Items.Add("❌ 退出", null, (s, e) =>
        {
            _isRunning = false;
            _trayIcon?.Dispose();
            Application.Exit();
            Environment.Exit(0);
        });
        _trayIcon.ContextMenuStrip = menu;

        _trayIcon.DoubleClick += (s, e) =>
        {
            this.WindowState = FormWindowState.Normal;
            this.ShowInTaskbar = true;
            this.BringToFront();
        };
    }
}