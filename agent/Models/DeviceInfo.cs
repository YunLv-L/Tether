using System;

namespace TetherAgent.Models;

public class DeviceInfo
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "未知设备";
    public string Ip { get; set; } = "";
    public string MachineCode { get; set; } = "";
    public bool IsOnline { get; set; } = false;
    public bool IsVerified { get; set; } = false;
    public DateTime LastSeen { get; set; } = DateTime.UtcNow;
    public string Note { get; set; } = "";

    public string DisplayName => string.IsNullOrEmpty(Note) ? Name : Note;
}