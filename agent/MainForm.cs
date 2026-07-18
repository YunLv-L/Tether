using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Diagnostics;
using System.Runtime.InteropServices;

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
    private const string BROADCAST_IP = "255.255.255.255";
    private const int BROADCAST_DURATION = 10;

    private readonly string _deviceName = Environment.MachineName;
    private readonly string _ipAddress = GetLocalIP();
    private TcpListener? _tcpListener;
    private bool _isRunning = true;
    private NotifyIcon? _trayIcon;
    private Thread? _udpThread;

    public MainForm()
    {
        InitializeComponent();
        SetupTrayIcon();
        UpdateStatus("正在启动...", false);
        Load += MainForm_Load;
        FormClosing += MainForm_FormClosing;
    }

    private void MainForm_Load(object? sender, EventArgs e)
    {
        // 显示设备信息
        lblDeviceName.Text = _deviceName;
        lblIP.Text = _ipAddress;
        lblPort.Text = TCP_PORT.ToString();
        lblStatus.Text = "● 运行中";
        lblStatus.ForeColor = Color.FromArgb(74, 222, 128); // 绿色

        // 启动服务
        StartServices();

        // 最小化到托盘（启动时不显示主窗口）
        this.WindowState = FormWindowState.Minimized;
        this.ShowInTaskbar = false;
        UpdateStatus("后台运行中", true);
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
        
        // 不使用 Abort，改用 Join 等待线程自然结束
        if (_udpThread != null && _udpThread.IsAlive)
        {
            _udpThread.Join(1000);  // 等待1秒
        }
        
        _trayIcon?.Dispose();
    }

    // ===== 启动服务 =====
    private void StartServices()
    {
        // UDP 广播
        _udpThread = new Thread(UdpBroadcastLoop)
        {
            IsBackground = true,
            Name = "UDP_Broadcast"
        };
        _udpThread.Start();

        // TCP 服务
        Thread tcpThread = new Thread(StartTcpServer)
        {
            IsBackground = true,
            Name = "TCP_Server"
        };
        tcpThread.Start();

        AppendLog("[服务] UDP 广播已启动");
        AppendLog($"[服务] TCP 服务监听端口 {TCP_PORT}");
        AppendLog($"[服务] 设备: {_deviceName} | IP: {_ipAddress}");
    }

    // ===== UDP 广播 =====
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
                AppendLog($"[UDP] 广播 #{count}");
                Thread.Sleep(2000);
            }
            AppendLog("[UDP] 广播结束 (TCP 服务持续运行)");
        }
        catch (Exception ex)
        {
            AppendLog($"[UDP] 错误: {ex.Message}");
        }
    }

    // ===== TCP 服务 =====
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
            AppendLog($"[TCP] 服务异常: {ex.Message}");
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
                    AppendLog($"[指令] 收到: {command} (来自 {client.Client.RemoteEndPoint})");
                    string result = ExecuteCommand(command);
                    AppendLog($"[执行] 结果: {result}");

                    byte[] response = Encoding.UTF8.GetBytes($"{result}\n");
                    stream.Write(response, 0, response.Length);
                    stream.Flush();
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"[TCP] 处理异常: {ex.Message}");
        }
    }

    // ===== 执行指令 =====
    private string ExecuteCommand(string command)
    {
        try
        {
            switch (command)
            {
                case "lock":
                    return LockWorkStation() ? "已锁定" : "锁定失败";
                case "sleep":
                    return SetSuspendState(false, true, false) ? "正在睡眠" : "睡眠失败";
                case "shutdown":
                    Process.Start("shutdown", "/s /t 3");
                    return "正在关机 (3秒后)";
                case "ping":
                    return "pong";
                default:
                    return $"未知指令: {command}";
            }
        }
        catch (Exception ex)
        {
            return $"执行失败: {ex.Message}";
        }
    }

    // ===== 工具方法 =====
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

    private void UpdateStatus(string text, bool isConnected)
    {
        if (lblStatus.InvokeRequired)
        {
            lblStatus.Invoke(() => UpdateStatus(text, isConnected));
            return;
        }
        lblStatus.Text = isConnected ? "● 运行中" : "● 未连接";
        lblStatus.ForeColor = isConnected ? Color.FromArgb(74, 222, 128) : Color.FromArgb(248, 113, 113);
    }

    // ===== 托盘 =====
    private void SetupTrayIcon()
    {
        _trayIcon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = $"Tether Agent\n设备: {_deviceName}",
            Visible = true
        };

        ContextMenuStrip menu = new ContextMenuStrip();
        menu.Items.Add("显示主窗口", null, (s, e) =>
        {
            this.WindowState = FormWindowState.Normal;
            this.ShowInTaskbar = true;
            this.BringToFront();
        });
        menu.Items.Add("-");
        menu.Items.Add("退出", null, (s, e) =>
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