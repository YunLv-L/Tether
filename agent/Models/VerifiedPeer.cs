using System;
using System.Net.Sockets;

namespace TetherAgent.Models;

public class VerifiedPeer
{
    public string MachineCode { get; set; } = "";
    public string Ip { get; set; } = "";
    public string DeviceName { get; set; } = "";
    public DateTime VerifiedAt { get; set; } = DateTime.UtcNow;
    public DateTime LastSeen { get; set; } = DateTime.UtcNow;
    public bool IsOnline { get; set; } = true;
    public TcpClient? TcpClient { get; set; }

    public DeviceInfo ToDeviceInfo()
    {
        return new DeviceInfo
        {
            Id = MachineCode,
            Name = DeviceName,
            Ip = Ip,
            MachineCode = MachineCode,
            IsOnline = IsOnline,
            IsVerified = true,
            LastSeen = LastSeen
        };
    }
}