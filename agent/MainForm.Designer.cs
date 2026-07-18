namespace TetherAgent;

partial class MainForm
{
    private System.ComponentModel.IContainer components = null;
    private TableLayoutPanel mainLayout;
    private Panel headerPanel;
    private Label lblTitle;
    private FlowLayoutPanel infoPanel;
    private Label lblDeviceLabel;
    private Label lblDeviceName;
    private Label lblIPLabel;
    private Label lblIP;
    private Button btnCopyIP;
    private Label lblPortLabel;
    private Label lblPort;
    private Label lblStatusLabel;
    private Label lblStatus;
    private Panel logPanel;
    private Label lblLogLabel;
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
        
        // ===== 窗体设置 =====
        this.Text = "Tether Agent";
        this.BackColor = Color.FromArgb(10, 20, 40);
        this.ForeColor = Color.White;
        this.MinimumSize = new Size(500, 420);
        this.Size = new Size(560, 540);
        this.StartPosition = FormStartPosition.CenterScreen;
        this.Font = new Font("Segoe UI", 9F, FontStyle.Regular, GraphicsUnit.Point);

        // ===== 主布局 =====
        this.mainLayout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 3,
            Padding = new Padding(12, 12, 12, 12),
            BackColor = Color.FromArgb(10, 20, 40)
        };
        this.mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 48F));
        this.mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 120F));
        this.mainLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
        this.mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 0F));

        // ===== 顶部标题栏 =====
        this.headerPanel = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(20, 40, 80),
            Margin = new Padding(0, 0, 0, 8)
        };
        this.lblTitle = new Label
        {
            Text = "⚡ Tether Agent",
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            Font = new Font("Segoe UI", 15F, FontStyle.Bold),
            ForeColor = Color.FromArgb(100, 180, 255),
            Padding = new Padding(16, 0, 0, 0)
        };
        this.headerPanel.Controls.Add(lblTitle);

        // ===== 信息卡片（流式布局，自动换行） =====
        this.infoPanel = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = true,
            Padding = new Padding(12, 8, 12, 8),
            BackColor = Color.FromArgb(20, 35, 65),
            Margin = new Padding(0, 0, 0, 8)
        };

        // 设备名
        this.lblDeviceLabel = new Label
        {
            Text = "💻",
            ForeColor = Color.FromArgb(150, 180, 220),
            Font = new Font("Segoe UI", 11F, FontStyle.Regular),
            AutoSize = true,
            Padding = new Padding(4, 4, 0, 4)
        };
        this.lblDeviceName = new Label
        {
            Text = "---",
            ForeColor = Color.White,
            Font = new Font("Segoe UI", 11F, FontStyle.Bold),
            AutoSize = true,
            Padding = new Padding(0, 4, 16, 4)
        };

        // IP
        this.lblIPLabel = new Label
        {
            Text = "🌐",
            ForeColor = Color.FromArgb(150, 180, 220),
            Font = new Font("Segoe UI", 11F, FontStyle.Regular),
            AutoSize = true,
            Padding = new Padding(4, 4, 0, 4)
        };
        this.lblIP = new Label
        {
            Text = "---",
            ForeColor = Color.FromArgb(100, 200, 255),
            Font = new Font("Segoe UI", 11F, FontStyle.Bold),
            AutoSize = true,
            Padding = new Padding(0, 4, 4, 4),
            Cursor = Cursors.Hand
        };
        this.lblIP.Click += (s, e) => { Clipboard.SetText(lblIP.Text); };

        // 端口
        this.lblPortLabel = new Label
        {
            Text = "🔌",
            ForeColor = Color.FromArgb(150, 180, 220),
            Font = new Font("Segoe UI", 11F, FontStyle.Regular),
            AutoSize = true,
            Padding = new Padding(4, 4, 0, 4)
        };
        this.lblPort = new Label
        {
            Text = "5556",
            ForeColor = Color.White,
            Font = new Font("Segoe UI", 11F, FontStyle.Bold),
            AutoSize = true,
            Padding = new Padding(0, 4, 16, 4)
        };

        // 状态
        this.lblStatusLabel = new Label
        {
            Text = "●",
            ForeColor = Color.FromArgb(74, 222, 128),
            Font = new Font("Segoe UI", 14F, FontStyle.Regular),
            AutoSize = true,
            Padding = new Padding(4, 4, 0, 4)
        };
        this.lblStatus = new Label
        {
            Text = "运行中",
            ForeColor = Color.FromArgb(74, 222, 128),
            Font = new Font("Segoe UI", 11F, FontStyle.Bold),
            AutoSize = true,
            Padding = new Padding(0, 4, 0, 4)
        };

        this.infoPanel.Controls.AddRange(new Control[] {
            lblDeviceLabel, lblDeviceName,
            lblIPLabel, lblIP,
            lblPortLabel, lblPort,
            lblStatusLabel, lblStatus
        });

        // ===== 日志区域 =====
        this.logPanel = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(12, 22, 45)
        };

        this.lblLogLabel = new Label
        {
            Text = "📋 日志",
            Dock = DockStyle.Top,
            Height = 28,
            TextAlign = ContentAlignment.MiddleLeft,
            Font = new Font("Segoe UI", 9F, FontStyle.Regular),
            ForeColor = Color.FromArgb(130, 160, 200),
            Padding = new Padding(12, 0, 0, 0),
            BackColor = Color.FromArgb(18, 32, 58)
        };

        this.txtLog = new TextBox
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(8, 16, 32),
            ForeColor = Color.FromArgb(180, 210, 240),
            Font = new Font("Consolas", 9F, FontStyle.Regular),
            BorderStyle = BorderStyle.None,
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            WordWrap = true,
            Padding = new Padding(12, 6, 12, 6),
            Text = "等待连接...\n"
        };

        this.logPanel.Controls.Add(txtLog);
        this.logPanel.Controls.Add(lblLogLabel);

        // ===== 组装 =====
        this.mainLayout.Controls.Add(headerPanel, 0, 0);
        this.mainLayout.Controls.Add(infoPanel, 0, 1);
        this.mainLayout.Controls.Add(logPanel, 0, 2);
        this.Controls.Add(mainLayout);

        // 窗体贴边
        this.Padding = new Padding(0);
    }
}