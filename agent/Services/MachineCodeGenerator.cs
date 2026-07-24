using System;
using System.Net.NetworkInformation;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Win32;

namespace TetherAgent.Services;

public static class MachineCodeGenerator
{
    public static string Generate()
    {
        try
        {
            var info = new StringBuilder();

            // CPU
            try
            {
                string cpu = Registry.GetValue(@"HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\CentralProcessor\0",
                    "ProcessorNameString", "")?.ToString() ?? "";
                info.Append(cpu);
            }
            catch { }

            // 主板
            try
            {
                string board = Registry.GetValue(@"HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\BIOS",
                    "BaseBoardProduct", "")?.ToString() ?? "";
                info.Append(board);
            }
            catch { }

            // MAC
            try
            {
                var interfaces = NetworkInterface.GetAllNetworkInterfaces();
                var up = System.Linq.Enumerable.FirstOrDefault(interfaces, n =>
                    n.OperationalStatus == OperationalStatus.Up &&
                    n.NetworkInterfaceType != NetworkInterfaceType.Loopback);
                if (up != null)
                    info.Append(up.GetPhysicalAddress()?.ToString() ?? "");
            }
            catch { }

            // MachineGuid
            try
            {
                string guid = Registry.GetValue(@"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Cryptography",
                    "MachineGuid", "")?.ToString() ?? "";
                info.Append(guid);
            }
            catch { }

            byte[] bytes = Encoding.UTF8.GetBytes(info.ToString());
            using var sha = SHA256.Create();
            byte[] hash = sha.ComputeHash(bytes);
            return Convert.ToBase64String(hash)
                .Replace("/", "_")
                .Replace("+", "-")
                .Substring(0, 16);
        }
        catch
        {
            return Guid.NewGuid().ToString("N").Substring(0, 16);
        }
    }
}