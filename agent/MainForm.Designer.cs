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
    private Button btnConnect;

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
        // 窗体设置（MD3 深色主题）
        // ============================================================
        this.Text = "Tether Agent";
        this.BackColor = Color.FromArgb(15, 23, 42);        // #0F172A
        this.ForeColor = Color.White;
        this.MinimumSize = new Size(500, 420);
        this.Size = new Size(560, 540);
        this.StartPosition = FormStartPosition.CenterScreen;
        this.Font = new Font("Segoe UI Variable", 9F, FontStyle.Regular, GraphicsUnit.Point);
        this.FormBorderStyle = FormBorderStyle.Sizable;

        // ============================================================
        // 主布局
        // ============================================================
        this.mainLayout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 4,
            Padding = new Padding(12, 12, 12, 12),
            BackColor = Color.FromArgb(15, 23, 42),
            Margin = new Padding(0)
        };
        this.mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 48F));
        this.mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 120F));
        this.mainLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 44F));
        this.mainLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));

        // ============================================================
        // 顶部标题栏（MD3 TopAppBar 风格）
        // ============================================================
        this.headerPanel = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(30, 41, 59),         // #1E293B
            Margin = new Padding(0, 0, 0, 8)
        };

        this.lblTitle = new Label
        {
            Text = "🔗 Tether Agent",
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            Font = new Font("Segoe UI Variable", 16F, FontStyle.Bold, GraphicsUnit.Point),
            ForeColor = Color.FromArgb(59, 130, 246),        // #3B82F6
            Padding = new Padding(16, 0, 0, 0)
        };
        this.headerPanel.Controls.Add(lblTitle);

        // ============================================================
        // 信息卡片（MD3 Card 风格）
        // ============================================================
        this.infoPanel = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = true,
            Padding = new Padding(16, 12, 16, 12),
            BackColor = Color.FromArgb(30, 41, 59),          // #1E293B
            Margin = new Padding(0, 0, 0, 8)
        };

        // 设备名
        this.lblDeviceLabel = new Label
        {
            Text = "💻",
            ForeColor = Color.FromArgb(148, 163, 184),
            Font = new Font("Segoe UI Variable", 11F, FontStyle.Regular),
            AutoSize = true,
            Padding = new Padding(4, 4, 0, 4)
        };
        this.lblDeviceName = new Label
        {
            Text = "---",
            ForeColor = Color.White,
            Font = new Font("Segoe UI Variable", 11F, FontStyle.Bold),
            AutoSize = true,
            Padding = new Padding(0, 4, 16, 4)
        };

        // IP
        this.lblIPLabel = new Label
        {
            Text = "🌐",
            ForeColor = Color.FromArgb(148, 163, 184),
            Font = new Font("Segoe UI Variable", 11F, FontStyle.Regular),
            AutoSize = true,
            Padding = new Padding(4, 4, 0, 4)
        };
        this.lblIP = new Label
        {
            Text = "---",
            ForeColor = Color.FromArgb(59, 130, 246),         // #3B82F6
            Font = new Font("Segoe UI Variable", 11F, FontStyle.Bold),
            AutoSize = true,
            Padding = new Padding(0, 4, 4, 4),
            Cursor = Cursors.Hand
        };
        this.lblIP.Click += (s, e) => { Clipboard.SetText(lblIP.Text); };

        // 端口
        this.lblPortLabel = new Label
        {
            Text = "🔌",
            ForeColor = Color.FromArgb(148, 163, 184),
            Font = new Font("Segoe UI Variable", 11F, FontStyle.Regular),
            AutoSize = true,
            Padding = new Padding(4, 4, 0, 4)
        };
        this.lblPort = new Label
        {
            Text = "5556",
            ForeColor = Color.White,
            Font = new Font("Segoe UI Variable", 11F, FontStyle.Bold),
            AutoSize = true,
            Padding = new Padding(0, 4, 16, 4)
        };

        // 状态
        this.lblStatusLabel = new Label
        {
            Text = "●",
            ForeColor = Color.FromArgb(74, 222, 128),
            Font = new Font("Segoe UI Variable", 14F, FontStyle.Regular),
            AutoSize = true,
            Padding = new Padding(4, 4, 0, 4)
        };
        this.lblStatus = new Label
        {
            Text = "运行中",
            ForeColor = Color.FromArgb(74, 222, 128),
            Font = new Font("Segoe UI Variable", 11F, FontStyle.Bold),
            AutoSize = true,
            Padding = new Padding(0, 4, 0, 4)
        };

        this.infoPanel.Controls.AddRange(new Control[] {
            lblDeviceLabel, lblDeviceName,
            lblIPLabel, lblIP,
            lblPortLabel, lblPort,
            lblStatusLabel, lblStatus
        });

        // ============================================================
        // 连接按钮行（MD3 Filled Button 风格）
        // ============================================================
        var btnPanel = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(15, 23, 42),
            Margin = new Padding(0, 0, 0, 8)
        };

        this.btnConnect = new Button
        {
            Text = "📡 重新广播",
            FlatStyle = FlatStyle.Flat,
            BackColor = Color.FromArgb(59, 130, 246),         // #3B82F6
            ForeColor = Color.White,
            Font = new Font("Segoe UI Variable", 10F, FontStyle.Bold, GraphicsUnit.Point),
            Cursor = Cursors.Hand,
            Size = new Size(140, 34),
            Location = new Point(16, 4),
            FlatAppearance = { BorderSize = 0 }
        };
        this.btnConnect.Click += (s, e) => { /* 触发重新广播 */ };

        btnPanel.Controls.Add(btnConnect);

        // ============================================================
        // 日志区域
        // ============================================================
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
            Font = new Font("Segoe UI Variable", 9F, FontStyle.Regular),
            ForeColor = Color.FromArgb(148, 163, 184),
            Padding = new Padding(12, 0, 0, 0),
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
            Padding = new Padding(12, 6, 12, 6),
            Text = "等待连接...\n"
        };

        this.logPanel.Controls.Add(txtLog);
        this.logPanel.Controls.Add(lblLogLabel);

        // ============================================================
        // 组装
        // ============================================================
        this.mainLayout.Controls.Add(headerPanel, 0, 0);
        this.mainLayout.Controls.Add(infoPanel, 0, 1);
        this.mainLayout.Controls.Add(btnPanel, 0, 2);
        this.mainLayout.Controls.Add(logPanel, 0, 3);
        this.Controls.Add(mainLayout);

        this.Padding = new Padding(0);
    }
}