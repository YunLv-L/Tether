using System.Diagnostics;
using System.Runtime.InteropServices;

namespace TetherAgent;

internal static class Program
{
    [STAThread]
    static void Main()
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        // 检查是否已有实例运行
        if (Process.GetProcessesByName(Process.GetCurrentProcess().ProcessName).Length > 1)
        {
            MessageBox.Show("Tether Agent 已在运行中", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        // 启动主窗口（默认最小化到托盘）
        Application.Run(new MainForm());
    }
}