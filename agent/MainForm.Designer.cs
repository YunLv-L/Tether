namespace TetherAgent;

partial class MainForm
{
    private System.ComponentModel.IContainer components = null;
    private Label lblTitle;
    private Panel panelHeader;
    private Panel panelDevice;
    private Label lblDeviceLabel;
    private Label lblDeviceName;
    private Label lblIPLabel;
    private Label lblIP;
    private Label lblPortLabel;
    private Label lblPort;
    private Label lblStatus;
    private Label lblStatusLabel;
    private TextBox txtLog;
    private Label lblLogLabel;
    private Button btnCopyIP;

    protected override void Dispose(bool disposing)
    {
        if (disposing && (components != null))
            components.Dispose();
        base.Dispose(disposing);
    }

    private void InitializeComponent()
    {
        this.BackColor = Color.FromArgb(15, 23, 42);  // #0F172A
        this.Font = new Font("Segoe UI", 9F, FontStyle.Regular, GraphicsUnit.Point);
        this.ForeColor = Color.White;
        this.FormBorderStyle = FormBorderStyle.FixedSingle;
        this.MaximizeBox = false;
        this.MinimizeBox = true;
        this.Size = new Size(520, 500);
        this.StartPosition = FormStartPosition.CenterScreen;
        this.Text = "Tether Agent";

        // ===== 顶部标题 =====
        panelHeader = new Panel
        {
            Dock = DockStyle.Top,
            Height = 50,
            BackColor = Color.FromArgb(30, 41, 59)
        };

        lblTitle = new Label
        {
            Text = "🔗 Tether Agent",
            Font = new Font("Segoe UI", 16, FontStyle.Bold),
            ForeColor = Color.FromArgb(59, 130, 246),
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            Padding = new Padding(16, 0, 0, 0)
        };
        panelHeader.Controls.Add(lblTitle);

        // ===== 设备信息卡片 =====
        panelDevice = new Panel
        {
            Dock = DockStyle.Top,
            Height = 150,
            Padding = new Padding(16),
            BackColor = Color.FromArgb(30, 41, 59),
            Margin = new Padding(12)
        };

        lblDeviceLabel = new Label
        {
            Text = "设备名称",
            ForeColor = Color.FromArgb(148, 163, 184),
            Font = new Font("Segoe UI", 9, FontStyle.Regular),
            Location = new Point(16, 12),
            Size = new Size(80, 20)
        };
        lblDeviceName = new Label
        {
            Text = "---",
            ForeColor = Color.White,
            Font = new Font("Segoe UI", 11, FontStyle.Bold),
            Location = new Point(16, 34),
            Size = new Size(300, 24)
        };

        lblIPLabel = new Label
        {
            Text = "IP 地址",
            ForeColor = Color.FromArgb(148, 163, 184),
            Font = new Font("Segoe UI", 9, FontStyle.Regular),
            Location = new Point(16, 66),
            Size = new Size(80, 20)
        };
        lblIP = new Label
        {
            Text = "---",
            ForeColor = Color.FromArgb(59, 130, 246),
            Font = new Font("Segoe UI", 11, FontStyle.Bold),
            Location = new Point(16, 88),
            Size = new Size(200, 24)
        };

        btnCopyIP = new Button
        {
            Text = "复制",
            FlatStyle = FlatStyle.Flat,
            BackColor = Color.FromArgb(59, 130, 246),
            ForeColor = Color.White,
            Font = new Font("Segoe UI", 8, FontStyle.Bold),
            Location = new Point(230, 88),
            Size = new Size(60, 24),
            FlatAppearance = { BorderSize = 0 }
        };
        btnCopyIP.Click += (s, e) => { Clipboard.SetText(lblIP.Text); };

        lblPortLabel = new Label
        {
            Text = "TCP 端口",
            ForeColor = Color.FromArgb(148, 163, 184),
            Font = new Font("Segoe UI", 9, FontStyle.Regular),
            Location = new Point(310, 66),
            Size = new Size(80, 20)
        };
        lblPort = new Label
        {
            Text = "5556",
            ForeColor = Color.White,
            Font = new Font("Segoe UI", 11, FontStyle.Bold),
            Location = new Point(310, 88),
            Size = new Size(100, 24)
        };

        lblStatusLabel = new Label
        {
            Text = "状态",
            ForeColor = Color.FromArgb(148, 163, 184),
            Font = new Font("Segoe UI", 9, FontStyle.Regular),
            Location = new Point(16, 120),
            Size = new Size(80, 20)
        };
        lblStatus = new Label
        {
            Text = "● 运行中",
            ForeColor = Color.FromArgb(74, 222, 128),
            Font = new Font("Segoe UI", 11, FontStyle.Bold),
            Location = new Point(16, 140),
            Size = new Size(150, 24)
        };

        panelDevice.Controls.AddRange(new Control[] {
            lblDeviceLabel, lblDeviceName,
            lblIPLabel, lblIP, btnCopyIP,
            lblPortLabel, lblPort,
            lblStatusLabel, lblStatus
        });

        // ===== 日志区域 =====
        lblLogLabel = new Label
        {
            Text = "📋 运行日志",
            ForeColor = Color.FromArgb(148, 163, 184),
            Font = new Font("Segoe UI", 9, FontStyle.Regular),
            Dock = DockStyle.Top,
            Padding = new Padding(16, 8, 0, 0),
            Height = 32
        };

        txtLog = new TextBox
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(15, 23, 42),
            ForeColor = Color.FromArgb(203, 213, 225),
            Font = new Font("Consolas", 9, FontStyle.Regular),
            BorderStyle = BorderStyle.None,
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            WordWrap = true,
            Padding = new Padding(16, 8, 16, 8)
        };

        // ===== 添加到窗体 =====
        this.Controls.Add(txtLog);
        this.Controls.Add(lblLogLabel);
        this.Controls.Add(panelDevice);
        this.Controls.Add(panelHeader);
    }
}