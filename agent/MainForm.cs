using System.Net;
using System.Net.Sockets;
using System.Net.NetworkInformation;
using System.Text;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32;
using System.Drawing.Imaging;
using System.Security.Cryptography;
using System.Collections.Concurrent;

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

    // ===== 端口配置 =====
    private const int UDP_PORT = 5555;          // 身份发现
    private const int TCP_PORT = 5556;          // 指令 + 握手
    private const int SCREEN_PORT = 5557;       // 画面流
    private const int QUALITY_PORT = 5558;      // 画质控制

    // ===== 时序参数 =====
    private const int AGENT_BROADCAST_INTERVAL = 30;  // Agent 30s 发一次
    private const int CACHE_TTL_SECONDS = 60;         // 包滞留 60s
    private const int HANDSHAKE_TIMEOUT_MS = 3000;    // 握手超时 3s
    private const int HEARTBEAT_INTERVAL = 10;        // 心跳 10s
    private const int MAX_CLIENTS = 5;                // 最大并发画面连接

    // ===== 状态 =====
    private readonly string _deviceName = Environment.MachineName;
    private readonly string _machineCode;
    private bool _isRunning = true;
    private NotifyIcon? _trayIcon;
    private int _currentQuality = 85;
    private int _qualityLevel = 1;

    // ===== 服务线程 =====
    private Thread? _udpServerThread;
    private Thread? _udpBroadcastThread;
    private Thread? _tcpServerThread;
    private Thread? _screenServerThread;
    private Thread? _qualityServerThread;
    private Thread? _heartbeatThread;

    // ===== 服务器 =====
    private TcpListener? _tcpListener;
    private TcpListener? _screenListener;
    private TcpListener? _qualityListener;

    // ===== 并发控制 =====
    private readonly SemaphoreSlim _screenSemaphore = new(MAX_CLIENTS, MAX_CLIENTS);

    // ===== 60s 滞留缓存 (自动去重) =====
    private readonly ConcurrentDictionary<string, CachedPeer> _peerCache = new();

    // ===== 已知设备 (已验证) =====
    private readonly ConcurrentDictionary<string, VerifiedPeer> _verifiedPeers = new();

    public MainForm()
    {
        _machineCode = GenerateMachineCode();
        InitializeComponent();
        SetupTrayIcon();
        Load += MainForm_Load;
        FormClosing += MainForm_FormClosing;
        UpdateAutoStartStatus();
    }

    // ==================== 启动 ====================
    private void MainForm_Load(object? sender, EventArgs e)
    {
        lblDeviceName.Text = _deviceName;
        lblIP.Text = GetLocalIP();
        StartAllServices();
        this.WindowState = FormWindowState.Minimized;
        this.ShowInTaskbar = false;
        AppendLog($"🚀 Tether Agent v1.0 启动");
        AppendLog($"🔑 MachineCode: {_machineCode}");
        AppendLog($"💻 设备: {_deviceName}");
        AppendLog($"📶 IP: {lblIP.Text}");
        AppendLog($"📡 UDP 发现端口: {UDP_PORT}");
        AppendLog($"🔌 TCP 指令端口: {TCP_PORT}");
        AppendLog($"🖥️ 画面传输端口: {SCREEN_PORT}");
        AppendLog($"🎛️ 画质控制端口: {QUALITY_PORT}");
        AppendLog($"⏱️ Agent 广播间隔: {AGENT_BROADCAST_INTERVAL}s");
        AppendLog($"⏱️ 缓存滞留: {CACHE_TTL_SECONDS}s");
        AppendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void StartAllServices()
    {
        _udpServerThread = new Thread(UdpServerLoop) { IsBackground = true, Name = "UDP-Server" };
        _udpServerThread.Start();

        _udpBroadcastThread = new Thread(UdpBroadcastLoop) { IsBackground = true, Name = "UDP-Broadcast" };
        _udpBroadcastThread.Start();

        _tcpServerThread = new Thread(TcpServerLoop) { IsBackground = true, Name = "TCP-Server" };
        _tcpServerThread.Start();

        _screenServerThread = new Thread(ScreenServerLoop) { IsBackground = true, Name = "Screen-Server" };
        _screenServerThread.Start();

        _qualityServerThread = new Thread(QualityServerLoop) { IsBackground = true, Name = "Quality-Server" };
        _qualityServerThread.Start();

        _heartbeatThread = new Thread(HeartbeatLoop) { IsBackground = true, Name = "Heartbeat" };
        _heartbeatThread.Start();
    }

    // ================================================================
    //  1. UDP 服务器（被动接收 Tether 的身份包）
    // ================================================================
    private void UdpServerLoop()
    {
        try
        {
            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            socket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            socket.Bind(new IPEndPoint(IPAddress.Any, UDP_PORT));
            socket.ReceiveTimeout = 1000;
            var buffer = new byte[2048];
            EndPoint remoteEndPoint = new IPEndPoint(IPAddress.Any, 0);

            AppendLog($"📡 UDP 服务器已启动 (端口 {UDP_PORT})");

            while (_isRunning)
            {
                try
                {
                    int len = socket.ReceiveFrom(buffer, ref remoteEndPoint);
                    var ip = ((IPEndPoint)remoteEndPoint).Address.ToString();
                    string data = Encoding.UTF8.GetString(buffer, 0, len);

                    // 只处理 Tether 的身份包
                    if (!data.StartsWith("TETHER|")) continue;

                    var packet = IdentityPacket.Parse(data, ip);
                    if (packet == null) continue;

                    // 检查是否为本地回环
                    if (IPAddress.IsLoopback(IPAddress.Parse(ip))) continue;

                    // 存入 60s 滞留缓存 (自动去重)
                    var cached = new CachedPeer
                    {
                        Packet = packet,
                        ReceivedAt = DateTime.UtcNow,
                        IsAgent = false  // 对方是 Tether 手机
                    };
                    _peerCache.AddOrUpdate(packet.MachineCode, cached, (_, _) => cached);

                    // 如果已经验证过，更新在线状态
                    if (_verifiedPeers.TryGetValue(packet.MachineCode, out var verified))
                    {
                        verified.LastSeen = DateTime.UtcNow;
                        verified.IsOnline = true;
                        AppendLog($"🔄 设备在线: {packet.DeviceName} ({packet.Ip})");
                    }
                    else
                    {
                        AppendLog($"📡 发现新设备: {packet.DeviceName} ({packet.Ip})");
                        // 自动发起三次握手
                        Task.Run(() => InitiateHandshake(packet));
                    }
                }
                catch (SocketException) { /* 超时继续 */ }
                catch (Exception ex)
                {
                    AppendLog($"⚠️ UDP 服务器异常: {ex.Message}");
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ UDP 服务器启动失败: {ex.Message}");
        }
    }

    // ================================================================
    //  2. UDP 广播（Agent 主动发身份包，30s 一次）
    // ================================================================
    private void UdpBroadcastLoop()
    {
        try
        {
            var localIps = GetAllLocalIPs();
            if (localIps.Count == 0)
            {
                AppendLog("⚠️ 无可用 IP，广播已禁用");
                return;
            }

            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            socket.EnableBroadcast = true;

            AppendLog($"📡 UDP 广播已启动 (间隔 {AGENT_BROADCAST_INTERVAL}s)");

            while (_isRunning)
            {
                try
                {
                    string message = $"TETHER_AGENT|{_deviceName}|{GetLocalIP()}|{_machineCode}|{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}";
                    byte[] data = Encoding.UTF8.GetBytes(message);

                    foreach (var ip in localIps)
                    {
                        try
                        {
                            var broadcast = GetBroadcastAddress(ip);
                            if (broadcast != null)
                            {
                                socket.SendTo(data, new IPEndPoint(broadcast, UDP_PORT));
                            }
                        }
                        catch { /* 单个网卡失败不影响 */ }
                    }

                    AppendLog($"📡 广播身份包 ({localIps.Count} 个网段)");
                }
                catch (Exception ex)
                {
                    AppendLog($"⚠️ 广播异常: {ex.Message}");
                }

                // 等待 30s (但如果线程被取消，快速退出)
                for (int i = 0; i < AGENT_BROADCAST_INTERVAL && _isRunning; i++)
                    Thread.Sleep(1000);
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ UDP 广播失败: {ex.Message}");
        }
    }

    // ================================================================
    //  3. TCP 服务（指令 + 三次握手验证）
    // ================================================================
    private void TcpServerLoop()
    {
        try
        {
            _tcpListener = new TcpListener(IPAddress.Any, TCP_PORT);
            _tcpListener.Server.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.NoDelay, true);
            _tcpListener.Start();

            AppendLog($"🔌 TCP 指令服务已启动 (端口 {TCP_PORT})");

            while (_isRunning)
            {
                try
                {
                    var client = _tcpListener.AcceptTcpClient();
                    Task.Run(() => HandleTcpClient(client));
                }
                catch (SocketException ex) when (!_isRunning) { break; }
                catch (Exception ex)
                {
                    AppendLog($"⚠️ TCP 接受异常: {ex.Message}");
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ TCP 服务异常: {ex.Message}");
        }
    }

    private void HandleTcpClient(TcpClient client)
    {
        try
        {
            client.NoDelay = true;
            client.ReceiveTimeout = HANDSHAKE_TIMEOUT_MS;
            client.SendTimeout = HANDSHAKE_TIMEOUT_MS;

            using (client)
            using (var stream = client.GetStream())
            {
                var remoteIp = ((IPEndPoint)client.Client.RemoteEndPoint).Address.ToString();

                // 读取数据（前 4 字节为长度，兼容协议）
                var buffer = new byte[1024];
                int bytesRead = stream.Read(buffer, 0, buffer.Length);
                if (bytesRead == 0) return;

                string request = Encoding.UTF8.GetString(buffer, 0, bytesRead).Trim();

                // ===== 三次握手验证 =====
                if (request.StartsWith("SYN|"))
                {
                    var parts = request.Split('|');
                    if (parts.Length >= 2)
                    {
                        string machineCode = parts[1];
                        string? deviceName = parts.Length >= 3 ? parts[2] : null;

                        // 检查是否在缓存中（60s 内出现过）
                        if (_peerCache.TryGetValue(machineCode, out var cached) ||
                            _verifiedPeers.TryGetValue(machineCode, out var verified))
                        {
                            // 发送 SYN-ACK
                            string synAck = $"SYN-ACK|{_machineCode}|{_deviceName}|{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}";
                            byte[] response = Encoding.UTF8.GetBytes(synAck + "\n");
                            stream.Write(response, 0, response.Length);
                            stream.Flush();

                            AppendLog($"✅ 三次握手: SYN-ACK 已发送 → {machineCode} ({remoteIp})");
                        }
                        else
                        {
                            // 不在缓存中，拒绝
                            string reject = $"REJECT|Unknown device";
                            byte[] response = Encoding.UTF8.GetBytes(reject + "\n");
                            stream.Write(response, 0, response.Length);
                            stream.Flush();
                            AppendLog($"❌ 三次握手拒绝: {machineCode} (不在缓存中)");
                            return;
                        }

                        // 等待最后的 ACK (或直接进入指令处理)
                        bytesRead = stream.Read(buffer, 0, buffer.Length);
                        if (bytesRead > 0)
                        {
                            string ack = Encoding.UTF8.GetString(buffer, 0, bytesRead).Trim();
                            if (ack.StartsWith("ACK|"))
                            {
                                var ackParts = ack.Split('|');
                                if (ackParts.Length >= 2 && ackParts[1] == _machineCode)
                                {
                                    // 验证完成！
                                    var peer = new VerifiedPeer
                                    {
                                        MachineCode = machineCode,
                                        Ip = remoteIp,
                                        DeviceName = deviceName ?? "未知设备",
                                        VerifiedAt = DateTime.UtcNow,
                                        LastSeen = DateTime.UtcNow,
                                        IsOnline = true,
                                        TcpClient = client
                                    };
                                    _verifiedPeers.AddOrUpdate(machineCode, peer, (_, _) => peer);

                                    // 从缓存中移除
                                    _peerCache.TryRemove(machineCode, out _);

                                    AppendLog($"🎯 三次握手完成! 设备已验证: {peer.DeviceName} ({remoteIp})");

                                    // 发送确认信息
                                    string connected = $"CONNECTED|{_deviceName}|{_machineCode}";
                                    byte[] connResp = Encoding.UTF8.GetBytes(connected + "\n");
                                    stream.Write(connResp, 0, connResp.Length);
                                    stream.Flush();

                                    // ===== 进入指令处理循环 =====
                                    ProcessCommands(stream, peer);
                                    return;
                                }
                            }
                        }
                    }
                }

                // ===== 普通指令 (兼容旧协议) =====
                if (request == "ping" || request == "lock" || request == "sleep" || request == "shutdown")
                {
                    AppendLog($"📨 指令: {request} ({remoteIp})");
                    string result = ExecuteCommand(request);
                    byte[] response = Encoding.UTF8.GetBytes($"{result}\n");
                    stream.Write(response, 0, response.Length);
                    stream.Flush();
                }
                else
                {
                    // 未知指令
                    string unknown = $"UNKNOWN|{request}";
                    byte[] response = Encoding.UTF8.GetBytes(unknown + "\n");
                    stream.Write(response, 0, response.Length);
                    stream.Flush();
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"⚠️ TCP 客户端处理异常: {ex.Message}");
        }
    }

    private void ProcessCommands(NetworkStream stream, VerifiedPeer peer)
    {
        try
        {
            var buffer = new byte[1024];
            while (_isRunning && peer.IsOnline)
            {
                int bytesRead = stream.Read(buffer, 0, buffer.Length);
                if (bytesRead == 0) break;

                string command = Encoding.UTF8.GetString(buffer, 0, bytesRead).Trim();
                if (string.IsNullOrEmpty(command)) continue;

                AppendLog($"📨 指令 ({peer.DeviceName}): {command}");

                // 心跳保活
                if (command == "PING")
                {
                    string pong = $"PONG|{_deviceName}|{_machineCode}";
                    byte[] resp = Encoding.UTF8.GetBytes(pong + "\n");
                    stream.Write(resp, 0, resp.Length);
                    stream.Flush();
                    peer.LastSeen = DateTime.UtcNow;
                    continue;
                }

                // 执行指令
                string result = ExecuteCommand(command);
                byte[] response = Encoding.UTF8.GetBytes($"{result}\n");
                stream.Write(response, 0, response.Length);
                stream.Flush();
            }
        }
        catch (Exception ex)
        {
            AppendLog($"⚠️ 指令处理断开: {ex.Message}");
            peer.IsOnline = false;
            _verifiedPeers.TryRemove(peer.MachineCode, out _);
        }
    }

    // ================================================================
    //  4. 发起三次握手 (Tether 发起时，Agent 主动响应)
    // ================================================================
    private void InitiateHandshake(IdentityPacket packet)
    {
        try
        {
            AppendLog($"🤝 发起三次握手: {packet.DeviceName} ({packet.Ip})");

            using var client = new TcpClient();
            client.NoDelay = true;
            client.ReceiveTimeout = HANDSHAKE_TIMEOUT_MS;
            client.SendTimeout = HANDSHAKE_TIMEOUT_MS;

            if (!client.ConnectAsync(packet.Ip, TCP_PORT).Wait(HANDSHAKE_TIMEOUT_MS))
            {
                AppendLog($"❌ 握手超时: {packet.DeviceName}");
                return;
            }

            using var stream = client.GetStream();

            // 发送 SYN
            string syn = $"SYN|{_machineCode}|{_deviceName}";
            byte[] synData = Encoding.UTF8.GetBytes(syn + "\n");
            stream.Write(synData, 0, synData.Length);
            stream.Flush();

            // 等待 SYN-ACK
            var buffer = new byte[1024];
            int bytesRead = stream.Read(buffer, 0, buffer.Length);
            if (bytesRead == 0)
            {
                AppendLog($"❌ 无 SYN-ACK 响应: {packet.DeviceName}");
                return;
            }

            string response = Encoding.UTF8.GetString(buffer, 0, bytesRead).Trim();
            if (response.StartsWith("SYN-ACK|"))
            {
                var parts = response.Split('|');
                if (parts.Length >= 2 && parts[1] == packet.MachineCode)
                {
                    // 发送 ACK
                    string ack = $"ACK|{_machineCode}";
                    byte[] ackData = Encoding.UTF8.GetBytes(ack + "\n");
                    stream.Write(ackData, 0, ackData.Length);
                    stream.Flush();

                    // 等待 CONNECTED
                    bytesRead = stream.Read(buffer, 0, buffer.Length);
                    if (bytesRead > 0)
                    {
                        string connected = Encoding.UTF8.GetString(buffer, 0, bytesRead).Trim();
                        if (connected.StartsWith("CONNECTED|"))
                        {
                            // 握手完成！保存为已验证设备
                            var peer = new VerifiedPeer
                            {
                                MachineCode = packet.MachineCode,
                                Ip = packet.Ip,
                                DeviceName = packet.DeviceName,
                                VerifiedAt = DateTime.UtcNow,
                                LastSeen = DateTime.UtcNow,
                                IsOnline = true
                            };
                            _verifiedPeers.AddOrUpdate(packet.MachineCode, peer, (_, _) => peer);
                            AppendLog($"✅ 三次握手完成! {packet.DeviceName} ({packet.Ip}) 已验证");
                        }
                    }
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ 握手失败: {ex.Message}");
        }
    }

    // ================================================================
    //  5. 画面传输服务 (带连接数限制 + 本地回环过滤)
    // ================================================================
    private void ScreenServerLoop()
    {
        try
        {
            _screenListener = new TcpListener(IPAddress.Any, SCREEN_PORT);
            _screenListener.Server.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.NoDelay, true);
            _screenListener.Start();

            AppendLog($"🖥️ 画面传输服务已启动 (端口 {SCREEN_PORT})");

            while (_isRunning)
            {
                try
                {
                    var client = _screenListener.AcceptTcpClient();
                    var remoteIp = ((IPEndPoint)client.Client.RemoteEndPoint).Address;

                    // 过滤本地回环
                    if (IPAddress.IsLoopback(remoteIp))
                    {
                        client.Close();
                        continue;
                    }

                    // 连接数限制
                    if (!_screenSemaphore.Wait(0))
                    {
                        AppendLog($"⚠️ 画面连接数已达上限 ({MAX_CLIENTS})，拒绝: {remoteIp}");
                        client.Close();
                        continue;
                    }

                    var clientCopy = client;
                    Task.Run(() =>
                    {
                        try
                        {
                            HandleScreenClient(clientCopy);
                        }
                        catch (Exception ex)
                        {
                            AppendLog($"❌ 画面传输异常: {ex.Message}");
                        }
                        finally
                        {
                            _screenSemaphore.Release();
                        }
                    });
                }
                catch (SocketException ex) when (!_isRunning) { break; }
                catch (Exception ex)
                {
                    AppendLog($"⚠️ 画面服务异常: {ex.Message}");
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ 画面服务崩溃: {ex.Message}");
        }
    }

    private void HandleScreenClient(TcpClient client)
    {
        try
        {
            using (client)
            using (var stream = client.GetStream())
            {
                client.NoDelay = true;
                client.ReceiveBufferSize = 8192;
                client.SendBufferSize = 8192;
                client.ReceiveTimeout = 5000;
                client.SendTimeout = 5000;

                // 先验证身份 (简单握手)
                var buffer = new byte[1024];
                int bytesRead = stream.Read(buffer, 0, buffer.Length);
                if (bytesRead == 0) return;

                string request = Encoding.UTF8.GetString(buffer, 0, bytesRead).Trim();
                if (request.StartsWith("SCREEN_SYN|"))
                {
                    var parts = request.Split('|');
                    if (parts.Length >= 2)
                    {
                        string machineCode = parts[1];
                        if (!_verifiedPeers.ContainsKey(machineCode))
                        {
                            // 未验证，拒绝
                            string reject = "SCREEN_REJECT|Unauthorized";
                            byte[] resp = Encoding.UTF8.GetBytes(reject + "\n");
                            stream.Write(resp, 0, resp.Length);
                            stream.Flush();
                            AppendLog($"❌ 画面连接拒绝: 未验证的设备 {machineCode}");
                            return;
                        }
                    }
                }

                // 获取分辨率
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

                // 发送分辨率
                string sizeInfo = $"{targetWidth}|{targetHeight}\n";
                byte[] sizeData = Encoding.UTF8.GetBytes(sizeInfo);
                stream.Write(sizeData, 0, sizeData.Length);
                stream.Flush();

                AppendLog($"🖥️ 画面客户端连接: {client.Client.RemoteEndPoint} ({targetWidth}x{targetHeight})");

                var stopwatch = new Stopwatch();
                stopwatch.Start();
                int targetFps = 30;
                int frameIntervalMs = 1000 / targetFps;
                int failCount = 0;

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
                    if (imageData == null)
                    {
                        failCount++;
                        if (failCount >= 3)
                        {
                            AppendLog($"⚠️ 截屏连续失败 {failCount} 次，断开");
                            break;
                        }
                        Thread.Sleep(100);
                        continue;
                    }
                    failCount = 0;

                    try
                    {
                        byte[] lengthBytes = BitConverter.GetBytes(imageData.Length);
                        stream.Write(lengthBytes, 0, 4);
                        stream.Write(imageData, 0, imageData.Length);
                        stream.Flush();
                    }
                    catch (IOException)
                    {
                        AppendLog($"❌ 客户端断开: {client.Client.RemoteEndPoint}");
                        break;
                    }
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ 画面传输异常: {ex.Message}");
        }
    }

    // ================================================================
    //  6. 画质控制服务
    // ================================================================
    private void QualityServerLoop()
    {
        try
        {
            _qualityListener = new TcpListener(IPAddress.Any, QUALITY_PORT);
            _qualityListener.Server.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.NoDelay, true);
            _qualityListener.Start();

            while (_isRunning)
            {
                try
                {
                    var client = _qualityListener.AcceptTcpClient();
                    Task.Run(() => HandleQualityClient(client));
                }
                catch (SocketException) when (!_isRunning) { break; }
                catch (Exception ex)
                {
                    AppendLog($"⚠️ 画质服务异常: {ex.Message}");
                }
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ 画质服务崩溃: {ex.Message}");
        }
    }

    private void HandleQualityClient(TcpClient client)
    {
        try
        {
            using (client)
            using (var stream = client.GetStream())
            {
                client.NoDelay = true;
                var buffer = new byte[1024];
                int bytesRead = stream.Read(buffer, 0, buffer.Length);
                if (bytesRead == 0) return;

                string command = Encoding.UTF8.GetString(buffer, 0, bytesRead).Trim();
                AppendLog($"🎛️ 画质指令: {command}");

                if (command.StartsWith("RES:"))
                {
                    int level = int.Parse(command.Substring(4));
                    _qualityLevel = Math.Clamp(level, 0, 2);
                    string levelName = _qualityLevel == 0 ? "流畅(720p)" :
                                      _qualityLevel == 1 ? "标准(1080p)" : "高清(原始)";
                    AppendLog($"🎛️ 分辨率模式: {levelName}");
                }
                else if (command.StartsWith("QUALITY:"))
                {
                    int quality = int.Parse(command.Substring(8));
                    _currentQuality = Math.Clamp(quality, 30, 95);
                    AppendLog($"🎛️ JPEG 质量: {_currentQuality}%");
                }

                byte[] response = Encoding.UTF8.GetBytes("OK\n");
                stream.Write(response, 0, response.Length);
                stream.Flush();
            }
        }
        catch (Exception ex)
        {
            AppendLog($"❌ 画质控制异常: {ex.Message}");
        }
    }

    // ================================================================
    //  7. 心跳保活 (每 10s 检查已验证设备)
    // ================================================================
    private void HeartbeatLoop()
    {
        while (_isRunning)
        {
            try
            {
                var now = DateTime.UtcNow;

                // 检查已验证设备是否超时 (超过 60s 无心跳则标记离线)
                foreach (var kv in _verifiedPeers)
                {
                    var peer = kv.Value;
                    if ((now - peer.LastSeen).TotalSeconds > 60)
                    {
                        if (peer.IsOnline)
                        {
                            peer.IsOnline = false;
                            AppendLog($"⏰ 设备离线: {peer.DeviceName} ({peer.Ip})");
                        }
                    }
                }

                // 清理缓存中的过期包 (60s TTL)
                var expiredKeys = _peerCache
                    .Where(kv => (now - kv.Value.ReceivedAt).TotalSeconds > CACHE_TTL_SECONDS)
                    .Select(kv => kv.Key)
                    .ToList();
                foreach (var key in expiredKeys)
                {
                    _peerCache.TryRemove(key, out _);
                }

                // 更新界面状态
                int onlineCount = _verifiedPeers.Count(p => p.Value.IsOnline);
                Invoke(() =>
                {
                    lblStatus.Text = $"运行中 ({onlineCount} 台在线)";
                    lblStatus.ForeColor = onlineCount > 0 ?
                        Color.FromArgb(74, 222, 128) :
                        Color.FromArgb(251, 191, 36);
                });

                // 检查是否真的有活性连接
                if (onlineCount == 0)
                {
                    // 广播一次，唤醒网络
                }
            }
            catch (Exception ex)
            {
                AppendLog($"⚠️ 心跳异常: {ex.Message}");
            }

            for (int i = 0; i < HEARTBEAT_INTERVAL && _isRunning; i++)
                Thread.Sleep(1000);
        }
    }

    // ================================================================
    //  8. 辅助方法
    // ================================================================

    private string GetLocalIP()
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

    private List<IPAddress> GetAllLocalIPs()
    {
        var result = new List<IPAddress>();
        try
        {
            var host = Dns.GetHostEntry(Dns.GetHostName());
            foreach (var ip in host.AddressList)
                if (ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))
                    result.Add(ip);
        }
        catch { }
        if (result.Count == 0) result.Add(IPAddress.Loopback);
        return result;
    }

    private IPAddress? GetBroadcastAddress(IPAddress ip)
    {
        try
        {
            var parts = ip.ToString().Split('.');
            if (parts.Length == 4)
                return IPAddress.Parse($"{parts[0]}.{parts[1]}.{parts[2]}.255");
        }
        catch { }
        return null;
    }

    private string GenerateMachineCode()
    {
        try
        {
            var info = new StringBuilder();
            try
            {
                string cpu = Registry.GetValue(@"HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\CentralProcessor\0",
                    "ProcessorNameString", "")?.ToString() ?? "";
                info.Append(cpu);
            }
            catch { }
            try
            {
                string board = Registry.GetValue(@"HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\BIOS",
                    "BaseBoardProduct", "")?.ToString() ?? "";
                info.Append(board);
            }
            catch { }
            try
            {
                var interfaces = NetworkInterface.GetAllNetworkInterfaces();
                var up = interfaces.FirstOrDefault(n =>
                    n.OperationalStatus == OperationalStatus.Up &&
                    n.NetworkInterfaceType != NetworkInterfaceType.Loopback);
                if (up != null)
                    info.Append(up.GetPhysicalAddress()?.ToString() ?? "");
            }
            catch { }
            try
            {
                string guid = Registry.GetValue(@"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Cryptography",
                    "MachineGuid", "")?.ToString() ?? "";
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

    private string ExecuteCommand(string command)
    {
        try
        {
            switch (command)
            {
                case "lock": return LockWorkStation() ? "已锁定" : "锁定失败";
                case "sleep": return SetSuspendState(false, true, false) ? "正在睡眠" : "睡眠失败";
                case "shutdown": Process.Start("shutdown", "/s /t 3"); return "正在关机 (3秒后)";
                case "ping": return $"pong|{_deviceName}|{GetLocalIP()}|{_machineCode}";
                default: return $"未知指令: {command}";
            }
        }
        catch (Exception ex)
        {
            return $"执行失败: {ex.Message}";
        }
    }

    private byte[]? CaptureScreen(int targetWidth, int targetHeight)
    {
        try
        {
            var screen = Screen.PrimaryScreen;
            if (screen == null) return null;

            int logicalWidth = screen.Bounds.Width;
            int logicalHeight = screen.Bounds.Height;

            using (var bitmap = new Bitmap(logicalWidth, logicalHeight))
            using (var g = Graphics.FromImage(bitmap))
            {
                g.CopyFromScreen(screen.Bounds.X, screen.Bounds.Y, 0, 0, screen.Bounds.Size);

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
        catch
        {
            return null;
        }
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

    // ================================================================
    //  9. 数据结构
    // ================================================================

    private class IdentityPacket
    {
        public string Type { get; set; } = "";
        public string DeviceName { get; set; } = "";
        public string Ip { get; set; } = "";
        public string MachineCode { get; set; } = "";
        public long Timestamp { get; set; }

        public static IdentityPacket? Parse(string data, string sourceIp)
        {
            try
            {
                // 格式: TETHER_AGENT|DeviceName|IP|MachineCode|Timestamp
                // 或: TETHER|DeviceName|IP|MachineCode|Timestamp
                var parts = data.Split('|');
                if (parts.Length < 5) return null;

                bool isAgent = parts[0] == "TETHER_AGENT";
                if (!isAgent && parts[0] != "TETHER") return null;

                return new IdentityPacket
                {
                    Type = parts[0],
                    DeviceName = parts[1],
                    Ip = string.IsNullOrEmpty(parts[2]) ? sourceIp : parts[2],
                    MachineCode = parts[3],
                    Timestamp = long.TryParse(parts[4], out long ts) ? ts : 0
                };
            }
            catch
            {
                return null;
            }
        }
    }

    private class CachedPeer
    {
        public IdentityPacket Packet { get; set; } = new();
        public DateTime ReceivedAt { get; set; }
        public bool IsAgent { get; set; }
    }

    private class VerifiedPeer
    {
        public string MachineCode { get; set; } = "";
        public string Ip { get; set; } = "";
        public string DeviceName { get; set; } = "";
        public DateTime VerifiedAt { get; set; }
        public DateTime LastSeen { get; set; }
        public bool IsOnline { get; set; }
        public TcpClient? TcpClient { get; set; }
    }

    // ================================================================
    //  10. 开机自启 + 系统托盘
    // ================================================================

    private const string REGISTRY_RUN_KEY = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run";
    private const string APP_NAME = "TetherAgent";

    private void UpdateAutoStartStatus() { }

    private bool IsAutoStartEnabled()
    {
        try
        {
            using (var key = Registry.CurrentUser.OpenSubKey(REGISTRY_RUN_KEY))
                return key?.GetValue(APP_NAME) != null;
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

    private void SetupTrayIcon()
    {
        _trayIcon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = $"Tether Agent\n{_deviceName}\n{GetLocalIP()}",
            Visible = true
        };

        var menu = new ContextMenuStrip();

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

        // 等待线程结束 (最多 2 秒)
        var threads = new[] { _udpServerThread, _udpBroadcastThread, _tcpServerThread,
                              _screenServerThread, _qualityServerThread, _heartbeatThread };
        foreach (var t in threads)
        {
            try { t?.Join(2000); } catch { }
        }

        _trayIcon?.Dispose();
    }
}