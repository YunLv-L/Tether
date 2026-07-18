namespace TetherAgent;

partial class MainForm
{
    private System.ComponentModel.IContainer components = null;

    // ===== 控件 =====
    private Panel headerPanel;
    private Label lblTitle;
    private Panel infoPanel;
    private Label lblDeviceLabel;
    private Label lblDeviceName;
    private Label lblIPLabel;
    private Label lblIP;
    private Label lblPortLabel;
    private Label lblPort;
    private Label lblStatusLabel;
    private Label lblStatus;
    private Button btnBroadcast;
    private Panel logPanel;
    private Label lblLogTitle;
    private TextBox txtLog;

    protected override void Dispose(bool disposing)
    {
        if (disposing && (components != null))
            components.Dispose();
        base.Dispose(disposing);
    }

    private void InitializeComponent()
    {
        this.components = new System.ComponentModel.Container();
        
        // ============================================================
        // 窗体设置
        // ============================================================
        this.Text = "Tether Agent";
        this.BackColor = Color.FromArgb(15, 23, 42);     // #0F172A
        this.ForeColor = Color.White;
        this.MinimumSize = new Size(480, 400);
        this.Size = new Size(560, 520);
        this.StartPosition = FormStartPosition.CenterScreen;
        this.Font = new Font("Segoe UI", 9F, FontStyle.Regular, GraphicsUnit.Point);
        this.FormBorderStyle = FormBorderStyle.Sizable;
        this.Padding = new Padding(0);

        // ============================================================
        // 标题栏
        // ============================================================
        this.headerPanel = new Panel
        {
            Dock = DockStyle.Top,
            Height = 56,
            BackColor = Color.FromArgb(30, 41, 59),     // #1E293B
            Padding = new Padding(16, 0, 16, 0)
        };

        this.lblTitle = new Label
        {
            Text = "⚡ Tether Agent",
            Dock = DockStyle.Left,
            TextAlign = ContentAlignment.MiddleLeft,
            Font = new Font("Segoe UI", 14F, FontStyle.Bold, GraphicsUnit.Point),
            ForeColor = Color.FromArgb(59, 130, 246),    // #3B82F6
            AutoSize = false,
            Width = 200
        };

        this.btnBroadcast = new Button
        {
            Text = "📡 广播",
            FlatStyle = FlatStyle.Flat,
            BackColor = Color.FromArgb(59, 130, 246),    // #3B82F6
            ForeColor = Color.White,
            Font = new Font("Segoe UI", 9F, FontStyle.Bold, GraphicsUnit.Point),
            Cursor = Cursors.Hand,
            Size = new Size(90, 32),
            Dock = DockStyle.Right,
            FlatAppearance = { BorderSize = 0 }
        };
        this.btnBroadcast.Click += (s, e) => { /* 触发广播 */ };

        this.headerPanel.Controls.Add(this.lblTitle);
        this.headerPanel.Controls.Add(this.btnBroadcast);

        // ============================================================
        // 信息卡片（圆角背景，用 Panel + 绘制）
        // ============================================================
        this.infoPanel = new Panel
        {
            Dock = DockStyle.Top,
            Height = 90,
            BackColor = Color.FromArgb(30, 41, 59),     // #1E293B
            Padding = new Padding(20, 16, 20, 16),
            Margin = new Padding(12, 12, 12, 8)
        };

        // 设备名
        this.lblDeviceLabel = new Label
        {
            Text = "💻 设备",
            ForeColor = Color.FromArgb(148, 163, 184),   // #94A3B8
            Font = new Font("Segoe UI", 9F, FontStyle.Regular, GraphicsUnit.Point),
            Location = new Point(20, 16),
            Size = new Size(60, 20)
        };
        this.lblDeviceName = new Label
        {
            Text = "---",
            ForeColor = Color.White,
            Font = new Font("Segoe UI", 12F, FontStyle.Bold, GraphicsUnit.Point),
            Location = new Point(20, 36),
            Size = new Size(200, 24)
        };

        // IP
        this.lblIPLabel = new Label
        {
            Text = "🌐 IP",
            ForeColor = Color.FromArgb(148, 163, 184),
            Font = new Font("Segoe UI", 9F, FontStyle.Regular, GraphicsUnit.Point),
            Location = new Point(260, 16),
            Size = new Size(50, 20)
        };
        this.lblIP = new Label
        {
            Text = "---",
            ForeColor = Color.FromArgb(59, 130, 246),    // #3B82F6
            Font = new Font("Segoe UI", 12F, FontStyle.Bold, GraphicsUnit.Point),
            Location = new Point(260, 36),
            Size = new Size(160, 24),
            Cursor = Cursors.Hand
        };
        this.lblIP.Click += (s, e) => 
        {
            Clipboard.SetText(lblIP.Text);
            MessageBox.Show("IP 已复制", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
        };

        // 状态
        this.lblStatusLabel = new Label
        {
            Text = "●",
            ForeColor = Color.FromArgb(74, 222, 128),    // 绿色圆点
            Font = new Font("Segoe UI", 14F, FontStyle.Regular, GraphicsUnit.Point),
            Location = new Point(20, 62),
            Size = new Size(20, 20)
        };
        this.lblStatus = new Label
        {
            Text = "运行中",
            ForeColor = Color.FromArgb(74, 222, 128),
            Font = new Font("Segoe UI", 10F, FontStyle.Bold, GraphicsUnit.Point),
            Location = new Point(44, 62),
            Size = new Size(80, 20)
        };

        this.infoPanel.Controls.AddRange(new Control[] {
            this.lblDeviceLabel, this.lblDeviceName,
            this.lblIPLabel, this.lblIP,
            this.lblStatusLabel, this.lblStatus
        });

        // ============================================================
        // 日志区域
        // ============================================================
        this.logPanel = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(12, 22, 45),
            Padding = new Padding(0, 0, 0, 0)
        };

        this.lblLogTitle = new Label
        {
            Text = "📋 日志",
            Dock = DockStyle.Top,
            Height = 32,
            TextAlign = ContentAlignment.MiddleLeft,
            Font = new Font("Segoe UI", 9F, FontStyle.Regular, GraphicsUnit.Point),
            ForeColor = Color.FromArgb(148, 163, 184),
            Padding = new Padding(16, 0, 0, 0),
            BackColor = Color.FromArgb(18, 32, 58)
        };

        this.txtLog = new TextBox
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(8, 16, 32),
            ForeColor = Color.FromArgb(203, 213, 225),
            Font = new Font("Consolas", 9F, FontStyle.Regular, GraphicsUnit.Point),
            BorderStyle = BorderStyle.None,
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            WordWrap = true,
            Padding = new Padding(16, 8, 16, 8),
            Text = "等待连接...\n"
        };

        this.logPanel.Controls.Add(this.txtLog);
        this.logPanel.Controls.Add(this.lblLogTitle);

        // ============================================================
        // 组装
        // ============================================================
        this.Controls.Add(this.logPanel);
        this.Controls.Add(this.infoPanel);
        this.Controls.Add(this.headerPanel);

        // 给 infoPanel 添加圆角效果（在 Paint 事件中画边框）
        this.infoPanel.Paint += (s, e) =>
        {
            var panel = s as Panel;
            if (panel == null) return;
            var rect = new Rectangle(0, 0, panel.Width - 1, panel.Height - 1);
            using (var pen = new Pen(Color.FromArgb(51, 65, 85), 1)) // #334155
            {
                int radius = 12;
                var path = GetRoundedRectangle(rect, radius);
                e.Graphics.DrawPath(pen, path);
            }
        };

        this.logPanel.Paint += (s, e) =>
        {
            var panel = s as Panel;
            if (panel == null) return;
            var rect = new Rectangle(0, 0, panel.Width - 1, panel.Height - 1);
            using (var pen = new Pen(Color.FromArgb(51, 65, 85), 1))
            {
                int radius = 8;
                var path = GetRoundedRectangle(rect, radius);
                e.Graphics.DrawPath(pen, path);
            }
        };
    }

    // ===== 圆角矩形工具方法 =====
    private static System.Drawing.Drawing2D.GraphicsPath GetRoundedRectangle(Rectangle rect, int radius)
    {
        var path = new System.Drawing.Drawing2D.GraphicsPath();
        path.AddArc(rect.X, rect.Y, radius * 2, radius * 2, 180, 90);
        path.AddArc(rect.Right - radius * 2, rect.Y, radius * 2, radius * 2, 270, 90);
        path.AddArc(rect.Right - radius * 2, rect.Bottom - radius * 2, radius * 2, radius * 2, 0, 90);
        path.AddArc(rect.X, rect.Bottom - radius * 2, radius * 2, radius * 2, 90, 90);
        path.CloseFigure();
        return path;
    }
}