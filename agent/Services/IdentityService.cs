using System;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using TetherAgent.Models;

namespace TetherAgent.Services;

public class IdentityService
{
    private const int UDP_PORT = 5555;
    private const int TCP_PORT = 5556;
    private const int CACHE_TTL_SECONDS = 60;
    private const int BROADCAST_INTERVAL = 30;
    private const int HANDSHAKE_TIMEOUT_MS = 5000;

    private readonly string _deviceName = Environment.MachineName;
    private readonly string _machineCode;
    private bool _isRunning = true;

    private readonly ConcurrentDictionary<string, CachedPeer> _peerCache = new();
    private readonly ConcurrentDictionary<string, VerifiedPeer> _verifiedPeers = new();

    public event Action<DeviceInfo>? OnDeviceFound;
    public event Action<DeviceInfo>? OnDeviceVerified;
    public event Action<DeviceInfo>? OnDeviceOffline;

    private Thread? _udpServerThread;
    private Thread? _udpBroadcastThread;
    private Thread? _tcpServerThread;
    private Thread? _heartbeatThread;

    public IdentityService()
    {
        _machineCode = MachineCodeGenerator.Generate();
    }

    public void Start()
    {
        _udpServerThread = new Thread(UdpServerLoop) { IsBackground = true };
        _udpServerThread.Start();

        _udpBroadcastThread = new Thread(UdpBroadcastLoop) { IsBackground = true };
        _udpBroadcastThread.Start();

        _tcpServerThread = new Thread(TcpServerLoop) { IsBackground = true };
        _tcpServerThread.Start();

        _heartbeatThread = new Thread(HeartbeatLoop) { IsBackground = true };
        _heartbeatThread.Start();
    }

    public void Stop()
    {
        _isRunning = false;
    }

    public void BroadcastDiscovery()
    {
        // 手动触发一次广播
        SendBroadcast();
    }

    public void SendCommand(string command)
    {
        // TODO: 发送指令到选中设备
        Console.WriteLine($"[Command] {command}");
    }

    // ========== UDP 广播 ==========
    private void UdpBroadcastLoop()
    {
        while (_isRunning)
        {
            SendBroadcast();
            Thread.Sleep(BROADCAST_INTERVAL * 1000);
        }
    }

    private void SendBroadcast()
    {
        try
        {
            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            socket.EnableBroadcast = true;

            string message = $"TETHER_AGENT|{_deviceName}|{GetLocalIP()}|{_machineCode}|{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}";
            byte[] data = Encoding.UTF8.GetBytes(message);

            var broadcast = IPAddress.Broadcast;
            socket.SendTo(data, new IPEndPoint(broadcast, UDP_PORT));
        }
        catch { }
    }

    // ========== UDP 服务器 ==========
    private void UdpServerLoop()
    {
        try
        {
            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            socket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            socket.Bind(new IPEndPoint(IPAddress.Any, UDP_PORT));

            var buffer = new byte[2048];
            EndPoint remoteEndPoint = new IPEndPoint(IPAddress.Any, 0);

            while (_isRunning)
            {
                try
                {
                    int len = socket.ReceiveFrom(buffer, ref remoteEndPoint);
                    string data = Encoding.UTF8.GetString(buffer, 0, len);
                    var ip = ((IPEndPoint)remoteEndPoint).Address.ToString();

                    if (!data.StartsWith("TETHER|")) continue;

                    var packet = ParseIdentityPacket(data, ip);
                    if (packet == null) continue;

                    if (IPAddress.IsLoopback(IPAddress.Parse(ip))) continue;

                    // 缓存设备
                    _peerCache.AddOrUpdate(packet.MachineCode,
                        new CachedPeer { Packet = packet, ReceivedAt = DateTime.UtcNow },
                        (_, old) => { old.ReceivedAt = DateTime.UtcNow; return old; });

                    // 触发发现事件
                    var device = new DeviceInfo
                    {
                        Id = packet.MachineCode,
                        Name = packet.DeviceName,
                        Ip = packet.Ip,
                        MachineCode = packet.MachineCode,
                        IsOnline = true,
                        LastSeen = DateTime.UtcNow
                    };
                    OnDeviceFound?.Invoke(device);

                    // 自动发起握手
                    Task.Run(() => InitiateHandshake(packet));
                }
                catch { }
            }
        }
        catch { }
    }

    // ========== TCP 服务器 ==========
    private void TcpServerLoop()
    {
        try
        {
            var listener = new TcpListener(IPAddress.Any, TCP_PORT);
            listener.Start();

            while (_isRunning)
            {
                try
                {
                    var client = listener.AcceptTcpClient();
                    Task.Run(() => HandleTcpClient(client));
                }
                catch { }
            }
        }
        catch { }
    }

    private void HandleTcpClient(TcpClient client)
    {
        // TODO: 处理 TCP 连接（接收指令、心跳等）
    }

    // ========== 心跳 ==========
    private void HeartbeatLoop()
    {
        while (_isRunning)
        {
            Thread.Sleep(10000);
            // TODO: 检查已验证设备状态，更新在线状态
        }
    }

    // ========== 握手 ==========
    private void InitiateHandshake(IdentityPacket packet)
    {
        // TODO: 实现完整握手逻辑
    }

    // ========== 工具方法 ==========
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

    private IdentityPacket? ParseIdentityPacket(string data, string sourceIp)
    {
        try
        {
            var parts = data.Split('|');
            if (parts.Length < 5) return null;
            if (parts[0] != "TETHER") return null;

            return new IdentityPacket
            {
                Type = parts[0],
                DeviceName = parts[1],
                Ip = string.IsNullOrEmpty(parts[2]) ? sourceIp : parts[2],
                MachineCode = parts[3],
                Timestamp = long.TryParse(parts[4], out var ts) ? ts : 0
            };
        }
        catch { return null; }
    }

    // ========== 内部类 ==========
    private class IdentityPacket
    {
        public string Type { get; set; } = "";
        public string DeviceName { get; set; } = "";
        public string Ip { get; set; } = "";
        public string MachineCode { get; set; } = "";
        public long Timestamp { get; set; }
    }

    private class CachedPeer
    {
        public IdentityPacket Packet { get; set; } = new();
        public DateTime ReceivedAt { get; set; }
    }
}