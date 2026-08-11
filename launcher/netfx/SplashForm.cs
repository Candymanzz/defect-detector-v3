using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

namespace ImlLauncher
{
    internal sealed class SplashForm : Form
    {
        private static readonly Color BgTop = Color.FromArgb(18, 20, 24);
        private static readonly Color BgBottom = Color.FromArgb(28, 32, 38);
        private static readonly Color Progress = Color.FromArgb(110, 180, 120);
        private static readonly Color TextPrimary = Color.FromArgb(236, 232, 224);
        private static readonly Color TextMuted = Color.FromArgb(150, 148, 140);
        private static readonly Color RowIdle = Color.FromArgb(40, 44, 52);
        private static readonly Color Ready = Color.FromArgb(110, 180, 120);
        private static readonly Color Error = Color.FromArgb(210, 90, 80);
        private static readonly Color Starting = Color.FromArgb(160, 170, 120);

        private readonly ServiceStatusModel _model;
        private StartupController _controller;
        private readonly PictureBox _logo;
        private readonly Label _brand;
        private readonly Label _subtitle;
        private readonly Label _step;
        private readonly Panel _progressTrack;
        private readonly Panel _progressFill;
        private readonly Panel _servicesHost;
        private readonly Button _stopButton;
        private readonly System.Windows.Forms.Timer _pulseTimer;
        private int _pulsePhase;
        private bool _stopping;
        private Image _logoImage;

        public SplashForm(ServiceStatusModel model)
        {
            _model = model;

            Text = "Defect Detector";
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            MinimizeBox = true;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(560, 640);
            DoubleBuffered = true;
            BackColor = BgTop;
            ForeColor = TextPrimary;
            Font = new Font("Segoe UI", 9.5f, FontStyle.Regular);
            ShowInTaskbar = true;
            try
            {
                Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            }
            catch
            {
            }

            _logo = new PictureBox();
            _logo.Location = new Point(28, 22);
            _logo.Size = new Size(64, 64);
            _logo.SizeMode = PictureBoxSizeMode.Zoom;
            _logo.BackColor = Color.Transparent;
            _logoImage = TryLoadEmbeddedLogo();
            if (_logoImage != null)
            {
                _logo.Image = _logoImage;
            }

            _brand = new Label();
            _brand.Text = "Defect Detector";
            _brand.Font = new Font("Segoe UI Semibold", 26f, FontStyle.Bold);
            _brand.ForeColor = TextPrimary;
            _brand.AutoSize = false;
            _brand.TextAlign = ContentAlignment.MiddleLeft;
            _brand.Location = new Point(104, 24);
            _brand.Size = new Size(420, 40);
            _brand.BackColor = Color.Transparent;

            _subtitle = new Label();
            _subtitle.Text = "Запуск системы инспекции";
            _subtitle.Font = new Font("Segoe UI", 11f, FontStyle.Regular);
            _subtitle.ForeColor = TextMuted;
            _subtitle.AutoSize = false;
            _subtitle.Location = new Point(106, 66);
            _subtitle.Size = new Size(420, 24);
            _subtitle.BackColor = Color.Transparent;

            _progressTrack = new Panel();
            _progressTrack.Location = new Point(36, 118);
            _progressTrack.Size = new Size(488, 8);
            _progressTrack.BackColor = RowIdle;

            _progressFill = new Panel();
            _progressFill.Location = new Point(0, 0);
            _progressFill.Size = new Size(8, 8);
            _progressFill.BackColor = Progress;
            _progressTrack.Controls.Add(_progressFill);

            _step = new Label();
            _step.Text = model.StepText;
            _step.Font = new Font("Segoe UI", 9.5f, FontStyle.Regular);
            _step.ForeColor = Progress;
            _step.AutoSize = false;
            _step.Location = new Point(36, 134);
            _step.Size = new Size(488, 22);
            _step.BackColor = Color.Transparent;

            _servicesHost = new Panel();
            _servicesHost.Location = new Point(28, 170);
            _servicesHost.Size = new Size(504, 390);
            _servicesHost.BackColor = Color.Transparent;
            _servicesHost.AutoScroll = false;

            _stopButton = new Button();
            _stopButton.Text = "Остановить систему";
            _stopButton.FlatStyle = FlatStyle.Flat;
            _stopButton.FlatAppearance.BorderColor = Color.FromArgb(90, 70, 60);
            _stopButton.FlatAppearance.BorderSize = 1;
            _stopButton.BackColor = Color.FromArgb(48, 36, 34);
            _stopButton.ForeColor = TextPrimary;
            _stopButton.Font = new Font("Segoe UI", 9.5f, FontStyle.Regular);
            _stopButton.Size = new Size(180, 36);
            _stopButton.Location = new Point(344, 576);
            _stopButton.Click += OnStopClick;

            Controls.Add(_logo);
            Controls.Add(_brand);
            Controls.Add(_subtitle);
            Controls.Add(_progressTrack);
            Controls.Add(_step);
            Controls.Add(_servicesHost);
            Controls.Add(_stopButton);

            BuildServiceRows();
            Paint += OnFormPaint;

            _pulseTimer = new System.Windows.Forms.Timer();
            _pulseTimer.Interval = 80;
            _pulseTimer.Tick += OnPulseTick;
            _pulseTimer.Start();

            FormClosing += OnFormClosing;
            Shown += OnShown;
        }

        public void AttachController(StartupController controller)
        {
            _controller = controller;
        }

        private static Image TryLoadEmbeddedLogo()
        {
            try
            {
                Assembly asm = typeof(SplashForm).Assembly;
                using (Stream stream = asm.GetManifestResourceStream("ImlLauncher.Assets.logo.png"))
                {
                    if (stream == null)
                    {
                        return null;
                    }
                    using (Image raw = Image.FromStream(stream))
                    {
                        return new Bitmap(raw);
                    }
                }
            }
            catch
            {
                return null;
            }
        }

        public void RefreshFromModel()
        {
            if (IsDisposed)
            {
                return;
            }
            if (InvokeRequired)
            {
                try
                {
                    BeginInvoke(new Action(RefreshFromModel));
                }
                catch
                {
                }
                return;
            }

            _step.Text = _model.StepText;
            int pct = _model.ProgressPercent;
            int width = Math.Max(8, (int)(_progressTrack.Width * (pct / 100.0)));
            _progressFill.Width = width;
            _progressFill.BackColor = _model.HasCriticalError ? Error : Progress;

            for (int i = 0; i < _servicesHost.Controls.Count; i++)
            {
                ServiceRowPanel row = _servicesHost.Controls[i] as ServiceRowPanel;
                if (row != null)
                {
                    row.RefreshFromItem();
                }
            }

            bool ready = _model.CriticalReady;
            if (ready && !_stopping)
            {
                _subtitle.Text = "Система запущена";
                _subtitle.ForeColor = Ready;
            }
            else if (_model.HasCriticalError)
            {
                _subtitle.Text = "Ошибка запуска";
                _subtitle.ForeColor = Error;
            }

            if (!string.IsNullOrEmpty(_controller != null ? _controller.FatalError : null) && !_stopping)
            {
                // keep stop enabled so user can close/cleanup
            }
        }

        private void BuildServiceRows()
        {
            _servicesHost.Controls.Clear();
            int y = 0;
            for (int i = 0; i < _model.Items.Count; i++)
            {
                ServiceRowPanel row = new ServiceRowPanel(_model.Items[i]);
                row.Location = new Point(0, y);
                row.Size = new Size(504, 46);
                _servicesHost.Controls.Add(row);
                y += 48;
            }
        }

        private void OnShown(object sender, EventArgs e)
        {
            if (_controller != null)
            {
                _controller.Start();
            }
        }

        private void OnPulseTick(object sender, EventArgs e)
        {
            _pulsePhase = (_pulsePhase + 1) % 40;
            for (int i = 0; i < _servicesHost.Controls.Count; i++)
            {
                ServiceRowPanel row = _servicesHost.Controls[i] as ServiceRowPanel;
                if (row != null)
                {
                    row.Pulse(_pulsePhase);
                }
            }
            // Subtle shimmer on incomplete progress
            if (_model.ProgressPercent < 100 && !_model.HasCriticalError)
            {
                float t = _pulsePhase / 40f;
                int a = 180 + (int)(40 * Math.Sin(t * Math.PI * 2));
                if (a < 160) a = 160;
                if (a > 255) a = 255;
                _progressFill.BackColor = Color.FromArgb(a, Progress.R, Progress.G, Progress.B);
            }
        }

        private void OnStopClick(object sender, EventArgs e)
        {
            BeginStop();
        }

        private void OnFormClosing(object sender, FormClosingEventArgs e)
        {
            if (_stopping)
            {
                return;
            }
            e.Cancel = true;
            BeginStop();
        }

        private void BeginStop()
        {
            if (_stopping)
            {
                return;
            }
            _stopping = true;
            _stopButton.Enabled = false;
            _step.Text = "Остановка системы…";
            _subtitle.Text = "Завершение процессов";
            _subtitle.ForeColor = TextMuted;
            Refresh();

            ThreadWorker stop = new ThreadWorker(delegate
            {
                try
                {
                    if (_controller != null)
                    {
                        _controller.RequestStop();
                    }
                }
                finally
                {
                    try
                    {
                        BeginInvoke(new Action(delegate
                        {
                            _pulseTimer.Stop();
                            FormClosing -= OnFormClosing;
                            if (_logo != null)
                            {
                                _logo.Image = null;
                            }
                            if (_logoImage != null)
                            {
                                _logoImage.Dispose();
                                _logoImage = null;
                            }
                            Close();
                        }));
                    }
                    catch
                    {
                        try { Close(); } catch { }
                    }
                }
            });
            stop.Start();
        }

        private void OnFormPaint(object sender, PaintEventArgs e)
        {
            using (LinearGradientBrush brush = new LinearGradientBrush(
                ClientRectangle, BgTop, BgBottom, LinearGradientMode.Vertical))
            {
                e.Graphics.FillRectangle(brush, ClientRectangle);
            }
            using (Pen pen = new Pen(Color.FromArgb(50, Progress), 2f))
            {
                e.Graphics.DrawLine(pen, 36, 108, 524, 108);
            }
        }

        private sealed class ThreadWorker
        {
            private readonly ThreadStart _start;
            public ThreadWorker(ThreadStart start)
            {
                _start = start;
            }
            public void Start()
            {
                Thread t = new Thread(_start);
                t.IsBackground = true;
                t.Start();
            }
        }

        private sealed class ServiceRowPanel : Panel
        {
            private readonly ServiceItem _item;
            private readonly Label _title;
            private readonly Label _state;
            private readonly Label _detail;
            private readonly Panel _dot;

            public ServiceRowPanel(ServiceItem item)
            {
                _item = item;
                BackColor = RowIdle;
                // Avoid SetStyle (protected) — DoubleBuffered via reflection-free approach:
                // just accept flicker on XP-era; modern WinForms Panel is fine.

                _dot = new Panel();
                _dot.Size = new Size(8, 8);
                _dot.Location = new Point(14, 19);
                _dot.BackColor = TextMuted;

                _title = new Label();
                _title.Text = item.Title;
                _title.Font = new Font("Segoe UI Semibold", 10f, FontStyle.Bold);
                _title.ForeColor = TextPrimary;
                _title.AutoSize = false;
                _title.Location = new Point(32, 6);
                _title.Size = new Size(280, 20);
                _title.BackColor = Color.Transparent;

                _state = new Label();
                _state.Text = item.StateLabel;
                _state.Font = new Font("Segoe UI", 9f, FontStyle.Regular);
                _state.ForeColor = TextMuted;
                _state.AutoSize = false;
                _state.TextAlign = ContentAlignment.MiddleRight;
                _state.Location = new Point(320, 6);
                _state.Size = new Size(170, 20);
                _state.BackColor = Color.Transparent;

                _detail = new Label();
                _detail.Text = item.Detail ?? "";
                _detail.Font = new Font("Segoe UI", 8f, FontStyle.Regular);
                _detail.ForeColor = TextMuted;
                _detail.AutoSize = false;
                _detail.Location = new Point(32, 26);
                _detail.Size = new Size(458, 16);
                _detail.BackColor = Color.Transparent;

                Controls.Add(_dot);
                Controls.Add(_title);
                Controls.Add(_state);
                Controls.Add(_detail);
            }

            public void RefreshFromItem()
            {
                _state.Text = _item.StateLabel;
                _detail.Text = _item.Detail ?? "";
                Color c = ColorForState(_item.State);
                _state.ForeColor = c;
                _dot.BackColor = c;
            }

            public void Pulse(int phase)
            {
                if (_item.State != ServiceState.Starting)
                {
                    return;
                }
                float t = phase / 40f;
                double k = 0.55 + 0.45 * (0.5 + 0.5 * Math.Sin(t * Math.PI * 2));
                int r = ClampByte((int)(Starting.R * k));
                int g = ClampByte((int)(Starting.G * k));
                int b = ClampByte((int)(Starting.B * k));
                _dot.BackColor = Color.FromArgb(r, g, b);
            }

            private static int ClampByte(int v)
            {
                if (v < 0) return 0;
                if (v > 255) return 255;
                return v;
            }

            private static Color ColorForState(ServiceState state)
            {
                switch (state)
                {
                    case ServiceState.Ready:
                        return Ready;
                    case ServiceState.Error:
                        return Error;
                    case ServiceState.Starting:
                        return Starting;
                    case ServiceState.Skipped:
                        return TextMuted;
                    default:
                        return TextMuted;
                }
            }
        }
    }
}
