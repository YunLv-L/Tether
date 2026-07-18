using System.Net;
using System.Net.Sockets;
using System.Net.NetworkInformation;
using System.Text;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32;
using System.Drawing.Imaging;
using System.Security.Cryptography;

namespace TetherAgent;

public partial class MainForm : Form
{
    // ===== Windows API =====
    [DllImport("user32.dll")]
    private static extern bool LockWorkStation();

    [DllImport("powrprof.dll", SetLastError = true)]
    private static extern bool SetSuspendState(bool hibernate, bool forceCritical, bool disableWakeEvent);

    [DllImport("gdi32.dll")]
    private static extern int GetDeviceCaps(IntPtr hdc, int nIndex);

    private const int HORZRES = 8;
    private const int VERTRES = 10;

    // ===== 配置 =====
    private const int UDP_PORT = 5555;
    private const int TCP_PORT = 5556;
    private const int SCREEN_PORT = 5557;
    private const int QUALITY_PORT = 5558;
    private const int MDNS_PORT = 5353;
    private const int BROADCAST_DURATION = 10;

    private readonly string _deviceName = Environment.MachineName;
    private readonly string _ipAddress = GetLocalIP();
    private readonly string _machineCode;
    private TcpListener? _tcpListener;
    private TcpListener? _screenListener;
    private TcpListener? _qualityListener;
    private bool _isRunning = true;
    private NotifyIcon? _trayIcon;
    private Thread? _udpThread;
    private Thread? _tcpThread;
    private Thread? _screenThread;
    private Thread? _qualityThread;
    private Thread? _mdnsThread;

    // 画质控制
    private int _currentQuality = 85;
    private int _qualityLevel = 1;

    public MainForm()
    {
        _machineCode = GenerateMachineCode();
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
        _qualityListener?.Stop();

        if (_udpThread != null && _udpThread.IsAlive)
            _udpThread.Join(1000);
        if (_tcpThread != null && _tcpThread.IsAlive)
            _tcpThread.Join(1000);
        if (_screenThread != null && _screenThread.IsAlive)
            _screenThread.Join(1000);
        if (_qualityThread != null && _qualityThread.IsAlive)
            _qualityThread.Join(1000);
        if (_mdnsThread != null && _mdnsThread.IsAlive)
            _mdnsThread.Join(1000);

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

        _qualityThread = new Thread(HandleQualityControl) { IsBackground = true };
        _qualityThread.Start();

        _mdnsThread = new Thread(MdnsResponseLoop) { IsBackground = true };
        _mdnsThread.Start();

        AppendLog("🚀 服务已启动");
        AppendLog($"📡 UDP 广播端口: {UDP_PORT}");
        AppendLog($"🔌 TCP 指令端口: {TCP_PORT}");
        AppendLog($"🖥️ 画面传输端口: {SCREEN_PORT}");
        AppendLog($"🎛️ 画质控制端口: {QUALITY_PORT}");
        AppendLog($"🔑 机器码: {_machineCode}");
        AppendLog($"💻 设备: {_deviceName}");
        AppendLog($"📶 IP: {_ipAddress}");
    }

    // ==================== 机器码生成 ====================
    private static string GenerateMachineCode()
    {
        try
        {
            var info = new StringBuilder();

            try
            {
                string cpu = Registry.GetValue(@"HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\CentralProcessor\0", "ProcessorNameString", "")?.ToString() ?? "";
                info.Append(cpu);
                string cpuId = Registry.GetValue(@"HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\CentralProcessor\0", "Identifier", "")?.ToString() ?? "";
                info.Append(cpuId);
            }
            catch { }

            try
            {
                string boardManu = Registry.GetValue(@"HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\BIOS", "BaseBoardManufacturer", "")?.ToString() ?? "";
                info.Append(boardManu);
                string boardProduct = Registry.GetValue(@"HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\BIOS", "BaseBoardProduct", "")?.ToString() ?? "";
                info.Append(boardProduct);
                string boardSerial = Registry.GetValue(@"HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\BIOS", "SystemSerialNumber", "")?.ToString() ?? "";
                info.Append(boardSerial);
            }
            catch { }

            try
            {
                var interfaces = NetworkInterface.GetAllNetworkInterfaces();
                var upInterface = interfaces.FirstOrDefault(n =>
                    n.OperationalStatus == OperationalStatus.Up &&
                    n.NetworkInterfaceType != NetworkInterfaceType.Loopback
                );
                if (upInterface != null)
                {
                    string mac = upInterface.GetPhysicalAddress()?.ToString() ?? "";
                    info.Append(mac);
                }
            }
            catch { }

            try
            {
                string guid = Registry.GetValue(@"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Cryptography", "MachineGuid", "")?.ToString() ?? "";
                info.Append(guid);
            }
            catch { }

            byte[] bytes = Encoding.UTF8.GetBytes(info.ToString());
            using (var sha = SHA256.Create())
            {
                byte[] hash = sha.ComputeHash(bytes);
                return Convert.ToBase64String(hash).Replace("/", "_").Replace("+", "-").Substring(0, 16);
            }
        }
        catch
        {
            return Guid.NewGuid().ToString("N").Substring(0, 16);
        }
    }

    // ==================== mDNS 响应 ====================
    private void MdnsResponseLoop()
    {
        try
        {
            using (var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp))
            {
                socket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                socket.Bind(new IPEndPoint(IPAddress.Any, MDNS_PORT));
                socket.ReceiveTimeout = 1000;

                var buffer = new byte[1024];
                EndPoint remoteEndPoint = new IPEndPoint(IPAddress.Any, 0);

                AppendLog($"📡 mDNS 监听已启动 (端口 {MDNS_PORT})");

                while (_isRunning)
                {
                    try
                    {
                        int received = socket.ReceiveFrom(buffer, ref remoteEndPoint);
                        string query = Encoding.UTF8.GetString(buffer, 0, received);

                        if (query.Contains("_tether._tcp") || query.Contains("TETHER"))
                        {
                            string response = $"TETHER_SERVICE|{_deviceName}|{_ipAddress}|{_machineCode}\n";
                            byte[] respData = Encoding.UTF8.GetBytes(response);
                            socket.SendTo(respData, remoteEndPoint);
                            AppendLog($"📡 mDNS 响应: {((IPEndPoint)remoteEndPoint).Address}");
                        }
                    }
                    catch (SocketException)
                    {
                        // 超时继续
                    }
                    catch (Exception ex)
                    {
                        AppendLog($"❌ mDNS 异常: {ex.Message}");
                    }
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ mDNS 启动失败: {ex.Message}");
        }
    }

    // ==================== UDP 广播 ====================
    private void UdpBroadcastLoop()
    {
        try
        {
            using var udpClient = new UdpClient();
            udpClient.EnableBroadcast = true;
            var endpoint = new IPEndPoint(IPAddress.Broadcast, UDP_PORT);
            string message = $"TETHER_AGENT|{_deviceName}|{_ipAddress}|{_machineCode}";
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
            _tcpListener.Server.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.NoDelay, true);
            _tcpListener.Start();

            while (_isRunning)
            {
                var client = _tcpListener.AcceptTcpClient();
                client.NoDelay = true;
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
                client.NoDelay = true;
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
                case "ping": return $"pong|{_deviceName}|{_ipAddress}|{_machineCode}";
                default: return $"未知指令: {command}";
            }
        }
        catch (Exception ex)
        {
            return $"执行失败: {ex.Message}";
        }
    }

    // ==================== 画质控制 ====================
    private void HandleQualityControl()
    {
        try
        {
            _qualityListener = new TcpListener(IPAddress.Any, QUALITY_PORT);
            _qualityListener.Server.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.NoDelay, true);
            _qualityListener.Start();

            while (_isRunning)
            {
                var client = _qualityListener.AcceptTcpClient();
                client.NoDelay = true;
                ThreadPool.QueueUserWorkItem(HandleQualityClient, client);
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ 画质控制异常: {ex.Message}");
        }
    }

    private void HandleQualityClient(object? state)
    {
        if (state is not TcpClient client) return;

        try
        {
            using (client)
            using (var stream = client.GetStream())
            {
                client.NoDelay = true;
                byte[] buffer = new byte[1024];
                int bytesRead = stream.Read(buffer, 0, buffer.Length);
                if (bytesRead > 0)
                {
                    string command = Encoding.UTF8.GetString(buffer, 0, bytesRead).Trim();
                    AppendLog($"🎛️ 画质指令: {command}");

                    if (command.StartsWith("QUALITY:"))
                    {
                        int newQuality = int.Parse(command.Substring(8));
                        _currentQuality = Math.Clamp(newQuality, 30, 95);
                        AppendLog($"🎛️ JPEG 质量: {_currentQuality}%");
                    }
                    else if (command.StartsWith("RES:"))
                    {
                        int level = int.Parse(command.Substring(4));
                        _qualityLevel = Math.Clamp(level, 0, 2);
                        string levelName = _qualityLevel == 0 ? "流畅(720p)" : _qualityLevel == 1 ? "标准(1080p)" : "高清(原始)";
                        AppendLog($"🎛️ 分辨率模式: {levelName}");
                    }

                    byte[] response = Encoding.UTF8.GetBytes($"OK\n");
                    stream.Write(response, 0, response.Length);
                    stream.Flush();
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ 画质控制异常: {ex.Message}");
        }
    }

    // ==================== 画面传输 ====================
    private void ScreenStreamLoop()
    {
        try
        {
            _screenListener = new TcpListener(IPAddress.Any, SCREEN_PORT);
            _screenListener.Server.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.NoDelay, true);
            _screenListener.Start();

            while (_isRunning)
            {
                var client = _screenListener.AcceptTcpClient();
                client.NoDelay = true;
                client.ReceiveBufferSize = 8192;
                client.SendBufferSize = 8192;
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
                // ===== 低延迟配置 =====
                client.NoDelay = true;
                client.ReceiveBufferSize = 8192;
                client.SendBufferSize = 8192;
                client.ReceiveTimeout = 5000;
                client.SendTimeout = 5000;

                int physicalWidth, physicalHeight;
                using (var graphics = Graphics.FromHwnd(IntPtr.Zero))
                {
                    var hdc = graphics.GetHdc();
                    physicalWidth = GetDeviceCaps(hdc, HORZRES);
                    physicalHeight = GetDeviceCaps(hdc, VERTRES);
                    graphics.ReleaseHdc(hdc);
                }

                if (physicalWidth <= 0 || physicalHeight <= 0)
                {
                    physicalWidth = SystemInformation.PrimaryMonitorSize.Width;
                    physicalHeight = SystemInformation.PrimaryMonitorSize.Height;
                }

                int targetWidth = physicalWidth;
                int targetHeight = physicalHeight;
                switch (_qualityLevel)
                {
                    case 0: targetWidth = 1280; targetHeight = 720; break;
                    case 1: targetWidth = 1920; targetHeight = 1080; break;
                    case 2: default: targetWidth = physicalWidth; targetHeight = physicalHeight; break;
                }

                var sizeInfo = $"{targetWidth}|{targetHeight}\n";
                byte[] sizeData = Encoding.UTF8.GetBytes(sizeInfo);
                stream.Write(sizeData, 0, sizeData.Length);
                stream.Flush();
                AppendLog($"🖥️ 画面客户端连接: {client.Client.RemoteEndPoint} ({targetWidth}x{targetHeight})");

                var stopwatch = new Stopwatch();
                stopwatch.Start();
                int targetFps = 30;
                int frameIntervalMs = 1000 / targetFps;

                while (_isRunning && client.Connected)
                {
                    long elapsed = stopwatch.ElapsedMilliseconds;
                    if (elapsed < frameIntervalMs)
                    {
                        int remaining = (int)(frameIntervalMs - elapsed);
                        if (remaining > 0 && remaining < 100)
                            Thread.Sleep(remaining);
                        continue;
                    }
                    stopwatch.Restart();

                    var imageData = CaptureScreen(targetWidth, targetHeight);
                    if (imageData == null) continue;

                    byte[] lengthBytes = BitConverter.GetBytes(imageData.Length);
                    stream.Write(lengthBytes, 0, 4);
                    stream.Write(imageData, 0, imageData.Length);
                    stream.Flush();
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ 画面传输断开: {ex.Message}");
        }
    }

    private Bitmap ScaleImage(Image original, int targetWidth, int targetHeight)
    {
        var result = new Bitmap(targetWidth, targetHeight);
        using (var g = Graphics.FromImage(result))
        {
            g.CompositingQuality = System.Drawing.Drawing2D.CompositingQuality.HighSpeed;
            g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.Low;
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.HighSpeed;
            g.DrawImage(original, 0, 0, targetWidth, targetHeight);
        }
        return result;
    }

    private byte[] EncodeJpeg(Bitmap bitmap)
    {
        using (var ms = new MemoryStream())
        {
            var codec = ImageCodecInfo.GetImageEncoders()
                .FirstOrDefault(c => c.FormatID == ImageFormat.Jpeg.Guid);

            if (codec == null)
            {
                bitmap.Save(ms, ImageFormat.Png);
                return ms.ToArray();
            }

            var encoderParams = new EncoderParameters(1);
            encoderParams.Param[0] = new EncoderParameter(
                System.Drawing.Imaging.Encoder.Quality,
                (long)_currentQuality
            );
            bitmap.Save(ms, codec, encoderParams);
            return ms.ToArray();
        }
    }

    private byte[]? CaptureScreen(int targetWidth, int targetHeight)
    {
        try
        {
            var screen = Screen.PrimaryScreen;
            if (screen == null) return null;

            int physicalWidth, physicalHeight;
            using (var graphics = Graphics.FromHwnd(IntPtr.Zero))
            {
                var hdc = graphics.GetHdc();
                physicalWidth = GetDeviceCaps(hdc, HORZRES);
                physicalHeight = GetDeviceCaps(hdc, VERTRES);
                graphics.ReleaseHdc(hdc);
            }

            if (physicalWidth <= 0 || physicalHeight <= 0)
            {
                physicalWidth = SystemInformation.PrimaryMonitorSize.Width;
                physicalHeight = SystemInformation.PrimaryMonitorSize.Height;
            }

            // 使用逻辑分辨率截屏，然后缩放到目标尺寸
            int logicalWidth = screen.Bounds.Width;
            int logicalHeight = screen.Bounds.Height;

            using (var bitmap = new Bitmap(logicalWidth, logicalHeight))
            using (var g = Graphics.FromImage(bitmap))
            {
                g.CopyFromScreen(screen.Bounds.X, screen.Bounds.Y, 0, 0, screen.Bounds.Size);

                // 缩放到目标尺寸
                if (targetWidth != logicalWidth || targetHeight != logicalHeight)
                {
                    using (var scaled = new Bitmap(targetWidth, targetHeight))
                    using (var sg = Graphics.FromImage(scaled))
                    {
                        sg.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
                        sg.DrawImage(bitmap, 0, 0, targetWidth, targetHeight);
                        return EncodeJpeg(scaled);
                    }
                }
                return EncodeJpeg(bitmap);
            }
        }
        catch (Exception)
        {
            return null;
        }
    }

    // ==================== 开机自启 ====================
    private const string REGISTRY_RUN_KEY = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run";
    private const string APP_NAME = "TetherAgent";

    private void UpdateAutoStartStatus() { }

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
        txtLog.AppendText($"[{time}] {message}\r\n");
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