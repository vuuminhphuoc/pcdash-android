using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace PcAgent
{
    /// <summary>
    /// Keeps PcAgent.exe up to date. Once a day it asks GitHub for the latest PCDash release; when that is
    /// newer than this build it downloads the agent zip and SHA256SUMS.txt, checks the zip against the
    /// listed checksum, swaps PcAgent.exe for the one inside and starts it again. Only PcAgent.exe is
    /// replaced (the installers, the CPU temperature helper and the token stay as they are).
    ///
    /// Off for a build with no version stamp (a build from source, version 0.0.0), when the environment has
    /// PCAGENT_NO_UPDATE=1, or when a file named "no-auto-update" sits next to PcAgent.exe.
    /// </summary>
    static class AgentUpdater
    {
        const string DefaultApi = "https://api.github.com/repos/vuuminhphuoc/pcdash-android/releases/latest";
        const string OfficialDownloads = "https://github.com/vuuminhphuoc/pcdash-android/releases/download/";
        const int MaxZipBytes = 40 * 1024 * 1024;
        const int MaxTextBytes = 256 * 1024;
        const int DefaultFirstDelaySeconds = 120;
        const int CheckIntervalSeconds = 24 * 3600;
        static readonly Regex TagPattern = new Regex("\"tag_name\"\\s*:\\s*\"v?([0-9]+(?:\\.[0-9]+){0,3})\"", RegexOptions.Compiled);
        static readonly Regex AssetPattern = new Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"", RegexOptions.Compiled);

        public static void Start(Action<string> log, Action stopListeners)
        {
            try { File.Delete(System.Reflection.Assembly.GetExecutingAssembly().Location + ".old"); } catch { }
            if (!Enabled(log)) return;
            var t = new Thread(() => Loop(log, stopListeners));
            t.IsBackground = true;
            t.Name = "PcAgentUpdater";
            t.Start();
        }

        static bool Enabled(Action<string> log)
        {
            if (Environment.GetEnvironmentVariable("PCAGENT_NO_UPDATE") == "1") return false;
            Version v;
            if (!Version.TryParse(AgentVersion.Current, out v) || Normalize(v) == new Version(0, 0, 0, 0)) return false;
            try
            {
                if (File.Exists(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "no-auto-update")))
                {
                    log("Auto-update is off (no-auto-update file).");
                    return false;
                }
            }
            catch { }
            return true;
        }

        static void Loop(Action<string> log, Action stopListeners)
        {
            int delay = DefaultFirstDelaySeconds;
            int envDelay;
            if (int.TryParse(Environment.GetEnvironmentVariable("PCAGENT_UPDATE_DELAY_SEC"), out envDelay) && envDelay >= 0) delay = envDelay;
            Thread.Sleep(delay * 1000);
            while (true)
            {
                try
                {
                    if (CheckAndApply(log, stopListeners)) return;
                }
                catch (Exception ex)
                {
                    log("Update check failed: " + ex.Message);
                }
                Thread.Sleep(CheckIntervalSeconds * 1000);
            }
        }

        // True when a new PcAgent.exe is in place and this process is about to exit.
        static bool CheckAndApply(Action<string> log, Action stopListeners)
        {
            ServicePointManager.SecurityProtocol |= (SecurityProtocolType)3072; // TLS 1.2
            string api = Environment.GetEnvironmentVariable("PCAGENT_UPDATE_API");
            if (string.IsNullOrEmpty(api)) api = DefaultApi;

            string release = DownloadText(api);
            Match tag = TagPattern.Match(release);
            if (!tag.Success) { log("Update check: no release tag found."); return false; }
            Version latest, current;
            if (!Version.TryParse(tag.Groups[1].Value, out latest) || !Version.TryParse(AgentVersion.Current, out current)) return false;
            if (Normalize(latest) <= Normalize(current))
            {
                log("Update check: " + AgentVersion.Current + " is the latest.");
                return false;
            }

            string zipUrl = null, sumsUrl = null;
            bool official = api == DefaultApi;
            foreach (Match m in AssetPattern.Matches(release))
            {
                string url = m.Groups[1].Value.Replace("\\/", "/");
                // From GitHub's own release API the files must be this project's release downloads.
                if (official && !url.StartsWith(OfficialDownloads, StringComparison.Ordinal)) continue;
                string name = url.Substring(url.LastIndexOf('/') + 1);
                if (name.StartsWith("pcdash-agent-windows-", StringComparison.OrdinalIgnoreCase) && name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) zipUrl = url;
                else if (string.Equals(name, "SHA256SUMS.txt", StringComparison.OrdinalIgnoreCase)) sumsUrl = url;
            }
            if (zipUrl == null || sumsUrl == null) { log("Update check: release " + latest + " has no agent zip or checksums."); return false; }

            string zipName = zipUrl.Substring(zipUrl.LastIndexOf('/') + 1);
            string expected = ChecksumFor(DownloadText(sumsUrl), zipName);
            if (expected == null) { log("Update refused: " + zipName + " is not in SHA256SUMS.txt."); return false; }

            string dir = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "update");
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
            Directory.CreateDirectory(dir);
            string zipPath = Path.Combine(dir, zipName);
            Download(zipUrl, zipPath, MaxZipBytes);
            string actual = Sha256Hex(zipPath);
            if (!string.Equals(actual, expected, StringComparison.OrdinalIgnoreCase))
            {
                log("Update refused: checksum of " + zipName + " does not match SHA256SUMS.txt.");
                Directory.Delete(dir, true);
                return false;
            }

            string newExe = Path.Combine(dir, "PcAgent.exe");
            if (!ExtractAgent(zipPath, newExe)) { log("Update refused: no PcAgent.exe in " + zipName + "."); Directory.Delete(dir, true); return false; }

            log("Updating PcAgent " + AgentVersion.Current + " -> " + latest + ".");
            string cur = SwapIn(newExe);
            Directory.Delete(dir, true);
            log("Swapped in the new PcAgent.exe; restarting.");
            // Stopping the listeners ends Main, and with it this background thread: schedule the restart first.
            StartAfterExit(cur);
            log("Restart is scheduled; exiting.");
            stopListeners();
            Environment.Exit(0);
            return true;
        }

        // 1.1 and 1.1.0 are the same version.
        static Version Normalize(Version v)
        {
            return new Version(v.Major, v.Minor, Math.Max(v.Build, 0), Math.Max(v.Revision, 0));
        }

        static string DownloadText(string url)
        {
            var ms = new MemoryStream();
            StreamTo(url, ms, MaxTextBytes);
            return Encoding.UTF8.GetString(ms.ToArray());
        }

        static void Download(string url, string path, int limit)
        {
            using (var fs = File.Create(path)) StreamTo(url, fs, limit);
        }

        static void StreamTo(string url, Stream dest, int limit)
        {
            var req = (HttpWebRequest)WebRequest.Create(url);
            req.UserAgent = "PcAgent-updater";
            req.Timeout = 30000;
            req.ReadWriteTimeout = 30000;
            req.AllowAutoRedirect = true;
            using (var res = (HttpWebResponse)req.GetResponse())
            using (var src = res.GetResponseStream())
            {
                byte[] buf = new byte[16384];
                int total = 0, n;
                while ((n = src.Read(buf, 0, buf.Length)) > 0)
                {
                    total += n;
                    if (total > limit) throw new IOException("download larger than " + limit + " bytes");
                    dest.Write(buf, 0, n);
                }
            }
        }

        // The line "<hex>  <name>" of SHA256SUMS.txt for the file, or null.
        static string ChecksumFor(string sums, string name)
        {
            foreach (string raw in sums.Split('\n'))
            {
                string line = raw.Trim();
                int sep = line.IndexOf(' ');
                if (sep != 64) continue;
                string file = line.Substring(sep).Trim().TrimStart('*');
                if (string.Equals(file, name, StringComparison.OrdinalIgnoreCase) && Regex.IsMatch(line.Substring(0, 64), "^[0-9a-fA-F]{64}$"))
                    return line.Substring(0, 64);
            }
            return null;
        }

        static string Sha256Hex(string path)
        {
            using (var sha = SHA256.Create())
            using (var fs = File.OpenRead(path))
            {
                byte[] h = sha.ComputeHash(fs);
                var sb = new StringBuilder(h.Length * 2);
                foreach (byte b in h) sb.Append(b.ToString("x2"));
                return sb.ToString();
            }
        }

        static bool ExtractAgent(string zipPath, string destExe)
        {
            using (var zip = ZipFile.OpenRead(zipPath))
            {
                foreach (var entry in zip.Entries)
                {
                    string name = entry.FullName.Replace('\\', '/');
                    if (!name.EndsWith("/PcAgent.exe", StringComparison.OrdinalIgnoreCase) && !string.Equals(name, "PcAgent.exe", StringComparison.OrdinalIgnoreCase)) continue;
                    if (entry.Length <= 0 || entry.Length > MaxZipBytes) return false;
                    using (var src = entry.Open())
                    using (var dst = File.Create(destExe)) src.CopyTo(dst);
                    return true;
                }
            }
            return false;
        }

        // A running exe can be renamed but not overwritten: move it aside and put the new one in its place.
        static string SwapIn(string newExe)
        {
            string cur = System.Reflection.Assembly.GetExecutingAssembly().Location;
            string old = cur + ".old";
            if (File.Exists(old)) File.Delete(old);
            File.Move(cur, old);
            try
            {
                File.Move(newExe, cur);
            }
            catch
            {
                File.Move(old, cur);
                throw;
            }
            return cur;
        }

        // Starts the exe once this process is gone (it would find the port still taken before that).
        static void StartAfterExit(string cur)
        {
            var psi = new System.Diagnostics.ProcessStartInfo("cmd.exe", "/c ping -n 4 127.0.0.1 >nul & start \"\" \"" + cur + "\"")
            {
                CreateNoWindow = true,
                UseShellExecute = false,
                WorkingDirectory = Path.GetDirectoryName(cur)
            };
            System.Diagnostics.Process.Start(psi);
        }
    }
}
