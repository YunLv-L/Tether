using System;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace TetherAgent.Services;

public class CommandService
{
    [DllImport("user32.dll")]
    private static extern bool LockWorkStation();

    [DllImport("powrprof.dll", SetLastError = true)]
    private static extern bool SetSuspendState(bool hibernate, bool forceCritical, bool disableWakeEvent);

    public string Execute(string command)
    {
        try
        {
            switch (command.ToLower())
            {
                case "lock":
                    return LockWorkStation() ? "已锁定" : "锁定失败";
                case "sleep":
                    return SetSuspendState(false, true, false) ? "正在睡眠" : "睡眠失败";
                case "shutdown":
                    Process.Start("shutdown", "/s /t 3");
                    return "正在关机 (3秒后)";
                case "ping":
                    return $"pong|{Environment.MachineName}|{GetLocalIP()}";
                default:
                    return $"未知指令: {command}";
            }
        }
        catch (Exception ex)
        {
            return $"执行失败: {ex.Message}";
        }
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
}