using System;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;

namespace PcAgent
{
    // "PcAgent.exe --setup": shows a QR code holding this PC's LAN address, MAC and agent token so the
    // PCDash host phone can be configured with a single camera scan (Cài đặt PC > Quét mã QR từ PC).
    public static class SetupWindow
    {
        const int DefaultPort = 8799;
        const int MinTokenLength = 16;
        const int QrBoxPx = 360;
        const int AutoCloseMinutes = 10;

        // English, or Vietnamese when Windows' display language is Vietnamese.
        static string T(string en, string vi)
        {
            return CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "vi" ? vi : en;
        }

        class Candidate
        {
            public string Label;
            public string Ip;
            public string Mac;
            public int Rank;
            public override string ToString() { return Label; }
        }

        public static void Run()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            string token = ReadToken();
            if (token == null)
            {
                MessageBox.Show(T("No agent token yet.\nRun agent\\install.bat again to create one, then reopen this window.",
                    "Chưa có token truy cập agent.\nHãy chạy lại agent\\install.bat để tạo token rồi mở lại cửa sổ này."),
                    "PCDash", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            List<Candidate> candidates = FindCandidates();
            if (candidates.Count == 0)
            {
                MessageBox.Show(T("No LAN connection found (Ethernet or Wi-Fi with a private address).\nConnect to the network, then reopen this window.",
                    "Không tìm thấy kết nối mạng LAN (Ethernet hoặc Wi-Fi có địa chỉ nội bộ).\nHãy kết nối mạng rồi mở lại cửa sổ này."),
                    "PCDash", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            int port = DefaultPort;
            string envPort = Environment.GetEnvironmentVariable("PCAGENT_PORT");
            int parsed;
            if (!string.IsNullOrEmpty(envPort) && int.TryParse(envPort, out parsed) && parsed > 0 && parsed <= 65535) port = parsed;

            Application.Run(new SetupForm(candidates, port, token));
        }

        class SetupForm : Form
        {
            readonly List<Candidate> candidates;
            readonly int port;
            readonly string token;
            readonly ComboBox adapterBox = new ComboBox();
            readonly PictureBox qrBox = new PictureBox();
            readonly Label infoLabel = new Label();
            readonly Timer autoClose = new Timer();

            public SetupForm(List<Candidate> candidates, int port, string token)
            {
                this.candidates = candidates;
                this.port = port;
                this.token = token;

                Text = T("PCDash - Scan to set up the host", "PCDash - Quét mã để cài đặt host");
                FormBorderStyle = FormBorderStyle.FixedDialog;
                MaximizeBox = false;
                MinimizeBox = false;
                StartPosition = FormStartPosition.CenterScreen;
                ClientSize = new Size(440, 630);
                BackColor = Color.White;
                Font = new Font("Segoe UI", 9.5f);

                Label title = new Label();
                title.Text = T("Scan this QR code with the host phone", "Quét mã QR bằng điện thoại host");
                title.Font = new Font("Segoe UI", 13f, FontStyle.Bold);
                title.SetBounds(20, 16, 400, 30);
                Controls.Add(title);

                Label steps = new Label();
                steps.Text = T("On the host phone: open PCDash > PC settings > Scan QR code from PC, then point the camera at the code below.",
                    "Trên điện thoại host: mở PCDash > Cài đặt PC > Quét mã QR từ PC, rồi hướng camera vào mã bên dưới.");
                steps.SetBounds(20, 50, 400, 44);
                Controls.Add(steps);

                adapterBox.DropDownStyle = ComboBoxStyle.DropDownList;
                adapterBox.SetBounds(20, 100, 400, 26);
                foreach (Candidate c in candidates) adapterBox.Items.Add(c);
                adapterBox.SelectedIndexChanged += delegate { Render(); };
                Controls.Add(adapterBox);

                qrBox.SetBounds(40, 136, QrBoxPx, QrBoxPx);
                qrBox.BackColor = Color.White;
                qrBox.SizeMode = PictureBoxSizeMode.CenterImage;
                Controls.Add(qrBox);

                infoLabel.SetBounds(20, 504, 400, 44);
                infoLabel.TextAlign = ContentAlignment.MiddleCenter;
                Controls.Add(infoLabel);

                Label warn = new Label();
                warn.Text = T("This code holds the agent's secret token. Do not screenshot or share it. The window closes by itself after " + AutoCloseMinutes + " minutes.",
                    "Mã này chứa token bí mật của agent. Đừng chụp màn hình hay chia sẻ. Cửa sổ tự đóng sau " + AutoCloseMinutes + " phút.");
                warn.ForeColor = Color.FromArgb(180, 40, 30);
                warn.SetBounds(20, 552, 400, 38);
                Controls.Add(warn);

                Button close = new Button();
                close.Text = T("Close", "Đóng");
                close.SetBounds(170, 592, 100, 28);
                close.Click += delegate { Close(); };
                Controls.Add(close);
                CancelButton = close;

                autoClose.Interval = AutoCloseMinutes * 60 * 1000;
                autoClose.Tick += delegate { Close(); };
                autoClose.Start();

                adapterBox.SelectedIndex = 0;
            }

            void Render()
            {
                Candidate c = adapterBox.SelectedItem as Candidate;
                if (c == null) return;
                string payload = BuildPayload(c.Ip, c.Mac, port, token);
                bool[,] modules = QrEncoder.Encode(Encoding.ASCII.GetBytes(payload));
                Image old = qrBox.Image;
                qrBox.Image = Draw(modules);
                if (old != null) old.Dispose();
                infoLabel.Text = "IP: " + c.Ip + "    MAC: " + c.Mac + "    " + T("Port: ", "Cổng: ") + port;
            }

            protected override void Dispose(bool disposing)
            {
                if (disposing)
                {
                    autoClose.Dispose();
                    if (qrBox.Image != null) qrBox.Image.Dispose();
                }
                base.Dispose(disposing);
            }
        }

        public static string BuildPayload(string ip, string mac, int port, string token)
        {
            return "pcdash://setup?v=1&ip=" + ip + "&mac=" + mac + "&port=" + port + "&token=" + token;
        }

        static Bitmap Draw(bool[,] modules)
        {
            int size = modules.GetLength(0);
            const int quiet = 4;
            int scale = Math.Max(1, QrBoxPx / (size + quiet * 2));
            int px = (size + quiet * 2) * scale;
            Bitmap bmp = new Bitmap(px, px);
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.Clear(Color.White);
                for (int y = 0; y < size; y++)
                {
                    for (int x = 0; x < size; x++)
                    {
                        if (modules[y, x]) g.FillRectangle(Brushes.Black, (x + quiet) * scale, (y + quiet) * scale, scale, scale);
                    }
                }
            }
            return bmp;
        }

        // Same sources as the agent itself: PCAGENT_TOKEN, else the first line of agent.token.
        static string ReadToken()
        {
            string token = Environment.GetEnvironmentVariable("PCAGENT_TOKEN");
            if (string.IsNullOrEmpty(token))
            {
                try
                {
                    string path = Environment.GetEnvironmentVariable("PCAGENT_TOKEN_FILE");
                    if (string.IsNullOrEmpty(path)) path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "agent.token");
                    if (File.Exists(path))
                    {
                        string[] lines = File.ReadAllLines(path);
                        if (lines.Length > 0) token = lines[0];
                    }
                }
                catch { }
            }
            token = (token ?? "").Trim();
            return token.Length >= MinTokenLength ? token : null;
        }

        static List<Candidate> FindCandidates()
        {
            List<Candidate> list = new List<Candidate>();
            foreach (NetworkInterface nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                try
                {
                    if (nic.OperationalStatus != OperationalStatus.Up) continue;
                    if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback || nic.NetworkInterfaceType == NetworkInterfaceType.Tunnel) continue;
                    if (Regex.IsMatch(nic.Name + " " + nic.Description, "virtual|vmware|hyper-v|vethernet|tailscale|vpn|wsl|docker|vbox|zerotier|loopback", RegexOptions.IgnoreCase)) continue;

                    IPInterfaceProperties props = nic.GetIPProperties();
                    bool hasGateway = false;
                    foreach (GatewayIPAddressInformation g in props.GatewayAddresses)
                    {
                        if (g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any)) hasGateway = true;
                    }
                    if (!hasGateway) continue;

                    byte[] macBytes = nic.GetPhysicalAddress().GetAddressBytes();
                    if (macBytes.Length != 6) continue;
                    string mac = BitConverter.ToString(macBytes).Replace('-', ':');
                    bool wired = nic.NetworkInterfaceType == NetworkInterfaceType.Ethernet
                        || nic.NetworkInterfaceType == NetworkInterfaceType.GigabitEthernet;

                    foreach (UnicastIPAddressInformation u in props.UnicastAddresses)
                    {
                        if (u.Address.AddressFamily != AddressFamily.InterNetwork || !IsPrivate(u.Address)) continue;
                        Candidate c = new Candidate();
                        c.Ip = u.Address.ToString();
                        c.Mac = mac;
                        c.Rank = wired ? 0 : 1;
                        c.Label = nic.Name + (wired ? T(" (wired)", " (dây)") : T(" (wireless)", " (không dây)")) + " - " + c.Ip;
                        list.Add(c);
                    }
                }
                catch { }
            }
            // Wake-on-LAN normally needs the wired adapter, so list it first.
            list.Sort(delegate (Candidate a, Candidate b) { return a.Rank.CompareTo(b.Rank); });
            return list;
        }

        static bool IsPrivate(IPAddress a)
        {
            byte[] b = a.GetAddressBytes();
            if (b[0] == 10) return true;
            if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) return true;
            return b[0] == 192 && b[1] == 168;
        }
    }
}
