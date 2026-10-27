using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Management;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using Microsoft.Win32;

namespace PcAgent
{
    public class SimpleRequest
    {
        public string HttpMethod;
        public string Path;
        public string RawUrl;
        public Dictionary<string, string> Headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        public Dictionary<string, string> QueryString = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        public string Body;
        public IPEndPoint RemoteEndPoint;
    }

    public class SimpleResponse
    {
        private readonly Stream stream;
        private bool sent;

        public int StatusCode { get; set; }
        public string ContentType { get; set; }
        public bool IsHead { get; set; }

        public SimpleResponse(Stream stream)
        {
            this.stream = stream;
            this.StatusCode = 200;
            this.ContentType = "text/plain; charset=utf-8";
        }

        public void Send(int code, string contentType, byte[] body)
        {
            if (sent) return;
            sent = true;
            StatusCode = code;
            ContentType = contentType;
            int len = body != null ? body.Length : 0;
            string statusText = Program.GetStatusText(code);
            string headers = "HTTP/1.1 " + code + " " + statusText + "\r\n" +
                             "Content-Type: " + contentType + "\r\n" +
                             "Content-Length: " + len + "\r\n" +
                             "Connection: close\r\n\r\n";
            byte[] hdrBytes = Encoding.ASCII.GetBytes(headers);
            try
            {
                stream.Write(hdrBytes, 0, hdrBytes.Length);
                if (!IsHead && len > 0)
                {
                    stream.Write(body, 0, len);
                }
                stream.Flush();
            }
            catch {}
        }
    }

    class Program
    {
        const int DefaultPort = 8799;
        const int MaxConcurrentConnections = 16;
        const int MaxHeaderBytes = 8192;
        const int MaxBodyBytes = 16384;
        const int ReadTimeoutMs = 5000;
        static int activeConnections = 0;
        static volatile bool running = true;
        static TcpListener listener4 = null;
        static TcpListener listener6 = null;
        const string DefaultEmptyMetricsJson = "{\"cpu\":{\"percent\":null,\"tempC\":null,\"name\":null},\"sys\":{\"tempC\":null},\"ram\":{\"usedMB\":null,\"totalMB\":null,\"percent\":null},\"gpu\":{\"percent\":null,\"tempC\":null,\"memUsedMB\":null,\"memTotalMB\":null,\"name\":null},\"net\":{\"downBps\":null,\"upBps\":null},\"disks\":[],\"uptimeSec\":null}";
        static volatile string latestMetricsJson = DefaultEmptyMetricsJson;
        static volatile string cachedQuotaJson = null;
        static volatile string lastQuotaError = null;
        static DateTime lastQuotaFetchUtc = DateTime.MinValue;
        static DateTime lastRefreshAttemptUtc = DateTime.MinValue;
        static readonly object quotaLock = new object();
        static volatile bool isFetchingQuota = false;

        [StructLayout(LayoutKind.Sequential)]
        struct MEMORYSTATUSEX
        {
            public uint dwLength;
            public uint dwMemoryLoad;
            public ulong ullTotalPhys;
            public ulong ullAvailPhys;
            public ulong ullTotalPageFile;
            public ulong ullAvailPageFile;
            public ulong ullTotalVirtual;
            public ulong ullAvailVirtual;
            public ulong ullAvailExtendedVirtual;
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX lpBuffer);

        [DllImport("kernel32.dll")]
        static extern ulong GetTickCount64();

        class NvidiaGpuInfo
        {
            public string Name;
            public double? TempC;
            public long? MemUsedMB;
            public long? MemTotalMB;
            public double? Percent;
        }

        class NicCounters
        {
            public PerformanceCounter Rx;
            public PerformanceCounter Tx;
        }

        const int MinTokenLength = 16;
        const string TokenHeader = "X-PcAgent-Token";
        static string agentToken;

        // Token comes from PCAGENT_TOKEN, else the first line of agent.token next to the exe
        // (or the file named by PCAGENT_TOKEN_FILE). Without a valid token the agent refuses
        // /metrics, /quota and /exec instead of trusting the local network.
        static void LoadAgentToken()
        {
            string token = Environment.GetEnvironmentVariable("PCAGENT_TOKEN");
            if (string.IsNullOrEmpty(token))
            {
                try
                {
                    string path = Environment.GetEnvironmentVariable("PCAGENT_TOKEN_FILE");
                    if (string.IsNullOrEmpty(path))
                    {
                        path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "agent.token");
                    }
                    if (File.Exists(path))
                    {
                        string[] lines = File.ReadAllLines(path);
                        if (lines.Length > 0) token = lines[0];
                    }
                }
                catch {}
            }
            token = (token ?? "").Trim();
            if (token.Length >= MinTokenLength)
            {
                agentToken = token;
                Log("Agent token loaded (" + token.Length + " chars)");
            }
            else
            {
                agentToken = null;
                Log("WARNING: no valid agent token (need at least " + MinTokenLength + " chars). /metrics, /quota and /exec are disabled.");
            }
        }

        static bool ConstantTimeEquals(string a, string b)
        {
            if (a == null || b == null) return false;
            int diff = a.Length ^ b.Length;
            int n = Math.Min(a.Length, b.Length);
            for (int i = 0; i < n; i++)
            {
                diff |= a[i] ^ b[i];
            }
            return diff == 0;
        }

        // Returns true when the request may proceed; otherwise a 403/503 response was already sent.
        static bool Authorize(SimpleRequest req, SimpleResponse res)
        {
            if (agentToken == null)
            {
                Log("  503 token_not_configured");
                RespondJson(res, 503, "{\"error\":\"token_not_configured\"}");
                return false;
            }
            string given;
            if (!req.Headers.TryGetValue(TokenHeader, out given) || !ConstantTimeEquals(agentToken, given.Trim()))
            {
                Log("  403 forbidden");
                RespondJson(res, 403, "{\"error\":\"forbidden\"}");
                return false;
            }
            return true;
        }

        static void Main(string[] args)
        {
            if (args.Length > 0 && string.Equals(args[0], "--setup", StringComparison.OrdinalIgnoreCase))
            {
                SetupWindow.Run();
                return;
            }

            Action stopListeners = () =>
            {
                running = false;
                try { if (listener4 != null) listener4.Stop(); } catch {}
                try { if (listener6 != null) listener6.Stop(); } catch {}
            };
            AppDomain.CurrentDomain.ProcessExit += (s, e) => stopListeners();
            Console.CancelKeyPress += (s, e) => stopListeners();

            int port = DefaultPort;
            string envPort = Environment.GetEnvironmentVariable("PCAGENT_PORT");
            if (!string.IsNullOrEmpty(envPort))
            {
                int p;
                if (int.TryParse(envPort, out p) && p > 0 && p <= 65535)
                {
                    port = p;
                }
            }

            string envBindLocal = Environment.GetEnvironmentVariable("PCAGENT_BIND_LOCAL");
            bool bindLocal = (envBindLocal == "1" || string.Equals(envBindLocal, "true", StringComparison.OrdinalIgnoreCase));

            LoadAgentToken();

            var metricsThread = new Thread(MetricsWorker);
            metricsThread.IsBackground = true;
            metricsThread.Name = "PcAgentMetrics";
            metricsThread.Start();

            try
            {
                IPAddress bindAddr4 = bindLocal ? IPAddress.Loopback : IPAddress.Any;
                listener4 = new TcpListener(bindAddr4, port);
                listener4.Start();
                Log("PcAgent listening on port " + port + (bindLocal ? " (local bind)" : " (any bind)"));

                if (Socket.OSSupportsIPv6)
                {
                    try
                    {
                        IPAddress bindAddr6 = bindLocal ? IPAddress.IPv6Loopback : IPAddress.IPv6Any;
                        listener6 = new TcpListener(bindAddr6, port);
                        listener6.Start();
                        var thread6 = new Thread(() => AcceptLoop(listener6));
                        thread6.IsBackground = true;
                        thread6.Name = "PcAgentListenerIPv6";
                        thread6.Start();
                    }
                    catch {}
                }

                AcceptLoop(listener4);
            }
            catch (Exception ex)
            {
                Log("FATAL: " + ex.Message);
                Environment.Exit(1);
            }
            finally
            {
                stopListeners();
            }
        }

        static void AcceptLoop(TcpListener listener)
        {
            while (running)
            {
                TcpClient client = null;
                try
                {
                    client = listener.AcceptTcpClient();
                }
                catch (SocketException) { if (!running) break; }
                catch (ObjectDisposedException) { break; }

                if (client == null) continue;

                if (Interlocked.Increment(ref activeConnections) > MaxConcurrentConnections)
                {
                    Interlocked.Decrement(ref activeConnections);
                    try
                    {
                        using (client)
                        using (var stream = client.GetStream())
                        {
                            byte[] resp503 = Encoding.ASCII.GetBytes("HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\nContent-Length: 19\r\n\r\nservice_unavailable");
                            stream.Write(resp503, 0, resp503.Length);
                            stream.Flush();
                        }
                    }
                    catch {}
                    continue;
                }

                ThreadPool.QueueUserWorkItem(_ =>
                {
                    try
                    {
                        using (client)
                        {
                            ProcessClient(client);
                        }
                    }
                    catch (Exception ex)
                    {
                        Log("Client error: " + ex.Message);
                    }
                    finally
                    {
                        Interlocked.Decrement(ref activeConnections);
                    }
                });
            }
        }

        static int FindHeaderEnd(byte[] buf, int len)
        {
            for (int i = 0; i < len - 1; i++)
            {
                if (i + 3 < len && buf[i] == (byte)'\r' && buf[i + 1] == (byte)'\n' && buf[i + 2] == (byte)'\r' && buf[i + 3] == (byte)'\n')
                {
                    return i + 4;
                }
                if (buf[i] == (byte)'\n' && buf[i + 1] == (byte)'\n')
                {
                    return i + 2;
                }
            }
            return -1;
        }

        static void ProcessClient(TcpClient client)
        {
            client.ReceiveTimeout = ReadTimeoutMs;
            client.SendTimeout = ReadTimeoutMs;
            try { client.LingerState = new LingerOption(true, 2); } catch {}
            NetworkStream stream = null;
            try
            {
                stream = client.GetStream();
            }
            catch
            {
                return;
            }

            byte[] headerBuffer = new byte[MaxHeaderBytes];
            int totalRead = 0;
            int headerEnd = -1;

            try
            {
                while (totalRead < headerBuffer.Length)
                {
                    int r = stream.Read(headerBuffer, totalRead, headerBuffer.Length - totalRead);
                    if (r <= 0) break;
                    totalRead += r;

                    headerEnd = FindHeaderEnd(headerBuffer, totalRead);
                    if (headerEnd >= 0) break;
                }
            }
            catch (IOException)
            {
                return;
            }
            catch (SocketException)
            {
                return;
            }

            if (headerEnd < 0)
            {
                if (totalRead >= headerBuffer.Length)
                {
                    byte[] resp431 = Encoding.ASCII.GetBytes("HTTP/1.1 431 Request Header Fields Too Large\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\nContent-Length: 16\r\n\r\nheader_too_large");
                    try
                    {
                        stream.Write(resp431, 0, resp431.Length);
                        stream.Flush();
                        client.Client.Shutdown(SocketShutdown.Send);
                        byte[] drain = new byte[1024];
                        while (stream.DataAvailable)
                        {
                            if (stream.Read(drain, 0, drain.Length) <= 0) break;
                        }
                    }
                    catch {}
                }
                return;
            }

            string headerText = Encoding.UTF8.GetString(headerBuffer, 0, headerEnd);
            string[] lines = headerText.Split(new[] { "\r\n", "\n" }, StringSplitOptions.None);
            if (lines.Length == 0 || string.IsNullOrEmpty(lines[0])) return;

            string reqLine = lines[0].Trim();
            string[] reqParts = reqLine.Split(' ');
            if (reqParts.Length < 2)
            {
                byte[] resp400 = Encoding.ASCII.GetBytes("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\nContent-Length: 11\r\n\r\nbad_request");
                try { stream.Write(resp400, 0, resp400.Length); stream.Flush(); } catch {}
                return;
            }

            string method = reqParts[0].Trim().ToUpperInvariant();
            string rawUrl = reqParts[1].Trim();

            if (rawUrl.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || rawUrl.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    var u = new Uri(rawUrl);
                    rawUrl = u.PathAndQuery;
                }
                catch {}
            }

            string path = rawUrl;
            var query = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            int qIndex = rawUrl.IndexOf('?');
            if (qIndex >= 0)
            {
                path = rawUrl.Substring(0, qIndex);
                string qStr = rawUrl.Substring(qIndex + 1);
                string[] qPairs = qStr.Split('&');
                foreach (var pair in qPairs)
                {
                    if (string.IsNullOrEmpty(pair)) continue;
                    int eq = pair.IndexOf('=');
                    if (eq >= 0)
                    {
                        string key = Uri.UnescapeDataString(pair.Substring(0, eq));
                        string val = Uri.UnescapeDataString(pair.Substring(eq + 1));
                        query[key] = val;
                    }
                    else
                    {
                        query[Uri.UnescapeDataString(pair)] = "";
                    }
                }
            }

            var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            for (int i = 1; i < lines.Length; i++)
            {
                string line = lines[i];
                if (string.IsNullOrEmpty(line)) break;
                int colon = line.IndexOf(':');
                if (colon > 0)
                {
                    string hName = line.Substring(0, colon).Trim();
                    string hVal = line.Substring(colon + 1).Trim();
                    headers[hName] = hVal;
                }
            }

            int contentLength = 0;
            string clStr;
            if (headers.TryGetValue("Content-Length", out clStr))
            {
                int.TryParse(clStr, out contentLength);
            }

            if (contentLength < 0)
            {
                byte[] resp400 = Encoding.ASCII.GetBytes("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\nContent-Length: 11\r\n\r\nbad_request");
                try { stream.Write(resp400, 0, resp400.Length); stream.Flush(); } catch {}
                return;
            }

            if (contentLength > MaxBodyBytes)
            {
                byte[] resp413 = Encoding.ASCII.GetBytes("HTTP/1.1 413 Payload Too Large\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\nContent-Length: 17\r\n\r\npayload_too_large");
                try
                {
                    stream.Write(resp413, 0, resp413.Length);
                    stream.Flush();
                    client.Client.Shutdown(SocketShutdown.Send);
                    client.ReceiveTimeout = 200;
                    byte[] drain = new byte[1024];
                    try
                    {
                        while (stream.Read(drain, 0, drain.Length) > 0) {}
                    }
                    catch {}
                }
                catch {}
                return;
            }

            int bodyBytesAlreadyRead = totalRead - headerEnd;
            string bodyStr = "";
            if (contentLength > 0)
            {
                byte[] bodyBuf = new byte[contentLength];
                int bodyRead = 0;
                if (bodyBytesAlreadyRead > 0)
                {
                    int toCopy = Math.Min(bodyBytesAlreadyRead, contentLength);
                    Array.Copy(headerBuffer, headerEnd, bodyBuf, 0, toCopy);
                    bodyRead = toCopy;
                }

                try
                {
                    while (bodyRead < contentLength)
                    {
                        int r = stream.Read(bodyBuf, bodyRead, contentLength - bodyRead);
                        if (r <= 0) break;
                        bodyRead += r;
                    }
                }
                catch (IOException) { return; }
                catch (SocketException) { return; }

                bodyStr = Encoding.UTF8.GetString(bodyBuf, 0, bodyRead);
            }

            IPEndPoint remoteEp = null;
            try { remoteEp = client.Client.RemoteEndPoint as IPEndPoint; } catch {}

            var req = new SimpleRequest
            {
                HttpMethod = method,
                Path = path,
                RawUrl = rawUrl,
                Headers = headers,
                QueryString = query,
                Body = bodyStr,
                RemoteEndPoint = remoteEp
            };

            var res = new SimpleResponse(stream)
            {
                IsHead = (method == "HEAD")
            };

            Handle(req, res);
            try
            {
                client.Client.Shutdown(SocketShutdown.Send);
            }
            catch {}
        }

        static void MetricsWorker()
        {
            string cpuName = GetCpuName();

            // Populate initial sample immediately with static/instant data
            try
            {
                long? initRamUsed, initRamTotal;
                double? initRamPct;
                SampleRam(out initRamUsed, out initRamTotal, out initRamPct);
                latestMetricsJson = BuildMetricsJson(
                    null, null, cpuName,
                    null,
                    initRamUsed, initRamTotal, initRamPct,
                    null, null, null, null, null,
                    0, 0,
                    SampleDisks(),
                    SampleUptime()
                );
            }
            catch {}

            PerformanceCounter cpuCounter = null;
            bool cpuCounterIsFallback = false;

            Action initCpu = () =>
            {
                try
                {
                    cpuCounter = new PerformanceCounter("Processor Information", "% Processor Utility", "_Total");
                    cpuCounter.NextValue();
                    cpuCounterIsFallback = false;
                }
                catch
                {
                    try { if (cpuCounter != null) cpuCounter.Dispose(); } catch {}
                    try
                    {
                        cpuCounter = new PerformanceCounter("Processor", "% Processor Time", "_Total");
                        cpuCounter.NextValue();
                        cpuCounterIsFallback = true;
                    }
                    catch
                    {
                        cpuCounter = null;
                    }
                }
            };

            try { initCpu(); } catch {}

            PerformanceCounterCategory gpuCat = null;
            InstanceDataCollection prevGpuData = null;

            PerformanceCounterCategory nicCat = null;
            var nicCounters = new Dictionary<string, NicCounters>(StringComparer.OrdinalIgnoreCase);

            double? cpuTempC = null;
            double? sysTempC = null;
            DateTime lastCpuTempCheck = DateTime.MinValue;
            List<PerformanceCounter> tzCounters = InitThermalZoneCounters();

            NvidiaGpuInfo cachedNvidiaInfo = null;
            DateTime lastNvidiaCheck = DateTime.MinValue;

            string[] cachedNicInstances = null;
            DateTime lastNicInstanceCheck = DateTime.MinValue;

            string cachedDisksJson = null;
            DateTime lastDiskCheck = DateTime.MinValue;
            while (running)
            {
                try
                {
                    // 1. CPU%
                    double? cpuPercent = null;
                    try
                    {
                        if (cpuCounter == null)
                        {
                            initCpu();
                        }
                        if (cpuCounter != null)
                        {
                            float val = cpuCounter.NextValue();
                            if (val < 0) val = 0;
                            if (val > 100) val = 100;
                            cpuPercent = Math.Round((double)val, 1);
                        }
                    }
                    catch
                    {
                        if (!cpuCounterIsFallback)
                        {
                            try
                            {
                                if (cpuCounter != null) cpuCounter.Dispose();
                                cpuCounter = new PerformanceCounter("Processor", "% Processor Time", "_Total");
                                cpuCounter.NextValue();
                                cpuCounterIsFallback = true;
                            }
                            catch
                            {
                                cpuCounter = null;
                            }
                        }
                        else
                        {
                            cpuCounter = null;
                        }
                    }

                    // 2. CPU Name
                    if (cpuName == null)
                    {
                        cpuName = GetCpuName();
                    }

                    // 3. CPU Temp & Sys Temp (every 3s)
                    if ((DateTime.UtcNow - lastCpuTempCheck).TotalSeconds >= 3.0)
                    {
                        lastCpuTempCheck = DateTime.UtcNow;
                        cpuTempC = GetCpuTemp();
                        sysTempC = SampleSysTemp(tzCounters);
                    }

                    // 4. RAM (GlobalMemoryStatusEx)
                    long? ramUsedMB = null;
                    long? ramTotalMB = null;
                    double? ramPercent = null;
                    SampleRam(out ramUsedMB, out ramTotalMB, out ramPercent);

                    // 5. GPU% from GPU Engine counter
                    double? gpuEnginePercent = null;
                    try
                    {
                        if (gpuCat == null)
                        {
                            gpuCat = new PerformanceCounterCategory("GPU Engine");
                        }
                        if (gpuCat != null)
                        {
                            var catData = gpuCat.ReadCategory();
                            if (catData != null && catData.Contains("utilization percentage"))
                            {
                                var curGpuData = catData["utilization percentage"];
                                if (prevGpuData != null)
                                {
                                    var groups = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
                                    foreach (string key in curGpuData.Keys)
                                    {
                                        int idx = key.IndexOf("engtype_", StringComparison.OrdinalIgnoreCase);
                                        if (idx < 0) continue;
                                        string eng = key.Substring(idx + 8);
                                        int nextUnder = eng.IndexOf('_');
                                        if (nextUnder >= 0) eng = eng.Substring(0, nextUnder);

                                        if (prevGpuData.Contains(key))
                                        {
                                            try
                                            {
                                                var s1 = prevGpuData[key].Sample;
                                                var s2 = curGpuData[key].Sample;
                                                float val = CounterSampleCalculator.ComputeCounterValue(s1, s2);
                                                if (val > 0)
                                                {
                                                    double cur = 0;
                                                    groups.TryGetValue(eng, out cur);
                                                    groups[eng] = cur + val;
                                                }
                                            }
                                            catch {}
                                        }
                                    }
                                    double maxVal = 0;
                                    foreach (var kv in groups)
                                    {
                                        if (kv.Value > maxVal) maxVal = kv.Value;
                                    }
                                    if (maxVal > 100.0) maxVal = 100.0;
                                    if (maxVal < 0.0) maxVal = 0.0;
                                    gpuEnginePercent = Math.Round(maxVal, 1);
                                }
                                prevGpuData = curGpuData;
                            }
                        }
                    }
                    catch
                    {
                        gpuEnginePercent = null;
                        prevGpuData = null;
                    }

                    // 6. nvidia-smi (every 6s)
                    if ((DateTime.UtcNow - lastNvidiaCheck).TotalSeconds >= 6.0)
                    {
                        lastNvidiaCheck = DateTime.UtcNow;
                        try
                        {
                            var info = QueryNvidiaSmi();
                            if (info != null)
                            {
                                cachedNvidiaInfo = info;
                            }
                        }
                        catch {}
                    }

                    double? finalGpuPercent = gpuEnginePercent;
                    if (!finalGpuPercent.HasValue && cachedNvidiaInfo != null)
                    {
                        finalGpuPercent = cachedNvidiaInfo.Percent;
                    }
                    double? gpuTempC = cachedNvidiaInfo != null ? cachedNvidiaInfo.TempC : null;
                    long? gpuMemUsedMB = cachedNvidiaInfo != null ? cachedNvidiaInfo.MemUsedMB : null;
                    long? gpuMemTotalMB = cachedNvidiaInfo != null ? cachedNvidiaInfo.MemTotalMB : null;
                    string gpuName = cachedNvidiaInfo != null ? cachedNvidiaInfo.Name : null;

                    // 7. Network Interface
                    long? netDownBps = null;
                    long? netUpBps = null;
                    try
                    {
                        if (nicCat == null)
                        {
                            nicCat = new PerformanceCounterCategory("Network Interface");
                        }
                        if (nicCat != null)
                        {
                            string[] insts = cachedNicInstances;
                            if (insts == null || (DateTime.UtcNow - lastNicInstanceCheck).TotalSeconds >= 15.0)
                            {
                                lastNicInstanceCheck = DateTime.UtcNow;
                                insts = nicCat.GetInstanceNames();
                                cachedNicInstances = insts;
                            }
                            double sumRx = 0;
                            double sumTx = 0;
                            bool anyValid = false;

                            var activeNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                            foreach (string inst in insts)
                            {
                                string lower = inst.ToLowerInvariant();
                                if (lower.Contains("loopback") || lower.Contains("isatap") || lower.Contains("teredo"))
                                    continue;

                                activeNames.Add(inst);
                                NicCounters counters;
                                if (!nicCounters.TryGetValue(inst, out counters))
                                {
                                    try
                                    {
                                        var rx = new PerformanceCounter("Network Interface", "Bytes Received/sec", inst);
                                        var tx = new PerformanceCounter("Network Interface", "Bytes Sent/sec", inst);
                                        rx.NextValue();
                                        tx.NextValue();
                                        counters = new NicCounters { Rx = rx, Tx = tx };
                                        nicCounters[inst] = counters;
                                    }
                                    catch
                                    {
                                        continue;
                                    }
                                }

                                try
                                {
                                    float rxVal = counters.Rx.NextValue();
                                    float txVal = counters.Tx.NextValue();
                                    if (rxVal > 0) sumRx += rxVal;
                                    if (txVal > 0) sumTx += txVal;
                                    anyValid = true;
                                }
                                catch {}
                            }

                            var toRemove = new List<string>();
                            foreach (var k in nicCounters.Keys)
                            {
                                if (!activeNames.Contains(k)) toRemove.Add(k);
                            }
                            foreach (var k in toRemove)
                            {
                                try { nicCounters[k].Rx.Dispose(); } catch {}
                                try { nicCounters[k].Tx.Dispose(); } catch {}
                                nicCounters.Remove(k);
                            }

                            if (anyValid || insts.Length > 0)
                            {
                                netDownBps = (long)Math.Round(sumRx);
                                netUpBps = (long)Math.Round(sumTx);
                            }
                        }
                    }
                    catch
                    {
                        netDownBps = null;
                        netUpBps = null;
                    }

                    // 8. Disks (cached 10s)
                    if (cachedDisksJson == null || (DateTime.UtcNow - lastDiskCheck).TotalSeconds >= 10.0)
                    {
                        lastDiskCheck = DateTime.UtcNow;
                        cachedDisksJson = SampleDisks();
                    }
                    string disksJson = cachedDisksJson;
                    // 9. Uptime
                    long? uptimeSec = SampleUptime();

                    latestMetricsJson = BuildMetricsJson(
                        cpuPercent, cpuTempC, cpuName,
                        sysTempC,
                        ramUsedMB, ramTotalMB, ramPercent,
                        finalGpuPercent, gpuTempC, gpuMemUsedMB, gpuMemTotalMB, gpuName,
                        netDownBps, netUpBps,
                        disksJson,
                        uptimeSec
                    );
                }
                catch (Exception ex)
                {
                    Log("MetricsWorker error: " + ex.Message);
                }

                Thread.Sleep(1000);
            }
            if (tzCounters != null)
            {
                foreach (var c in tzCounters)
                {
                    try { c.Dispose(); } catch {}
                }
                tzCounters.Clear();
            }
        }

        static void SampleRam(out long? usedMB, out long? totalMB, out double? percent)
        {
            usedMB = null;
            totalMB = null;
            percent = null;
            try
            {
                var mem = new MEMORYSTATUSEX();
                mem.dwLength = (uint)Marshal.SizeOf(typeof(MEMORYSTATUSEX));
                if (GlobalMemoryStatusEx(ref mem))
                {
                    long tMB = (long)(mem.ullTotalPhys / (1024UL * 1024UL));
                    long aMB = (long)(mem.ullAvailPhys / (1024UL * 1024UL));
                    long uMB = tMB - aMB;
                    double pct = tMB > 0 ? ((double)uMB * 100.0 / (double)tMB) : 0.0;
                    if (pct < 0) pct = 0;
                    if (pct > 100) pct = 100;

                    totalMB = tMB;
                    usedMB = uMB;
                    percent = Math.Round(pct, 1);
                }
            }
            catch {}
        }

        static string SampleDisks()
        {
            var disksList = new List<string>();
            try
            {
                foreach (var drive in DriveInfo.GetDrives())
                {
                    try
                    {
                        if (drive.DriveType == DriveType.Fixed && drive.IsReady)
                        {
                            string name = drive.Name.TrimEnd('\\');
                            double totalGB = Math.Round((double)drive.TotalSize / (1024.0 * 1024.0 * 1024.0), 1);
                            double freeGB = (double)drive.TotalFreeSpace / (1024.0 * 1024.0 * 1024.0);
                            double usedGB = Math.Round(Math.Max(0.0, totalGB - freeGB), 1);
                            disksList.Add(string.Format(CultureInfo.InvariantCulture,
                                "{{\"name\":{0},\"usedGB\":{1},\"totalGB\":{2}}}",
                                EscapeJson(name),
                                FormatNum(usedGB),
                                FormatNum(totalGB)));
                        }
                    }
                    catch {}
                }
            }
            catch {}
            return "[" + string.Join(",", disksList.ToArray()) + "]";
        }

        static long? SampleUptime()
        {
            try
            {
                return (long)(GetTickCount64() / 1000UL);
            }
            catch
            {
                return null;
            }
        }

        static string BuildMetricsJson(
            double? cpuPercent, double? cpuTempC, string cpuName,
            double? sysTempC,
            long? ramUsedMB, long? ramTotalMB, double? ramPercent,
            double? gpuPercent, double? gpuTempC, long? gpuMemUsedMB, long? gpuMemTotalMB, string gpuName,
            long? netDownBps, long? netUpBps,
            string disksJson,
            long? uptimeSec)
        {
            var sb = new StringBuilder(512);
            sb.Append("{");

            // cpu
            sb.Append("\"cpu\":{");
            sb.Append("\"percent\":").Append(FormatNum(cpuPercent)).Append(",");
            sb.Append("\"tempC\":").Append(FormatNum(cpuTempC)).Append(",");
            sb.Append("\"name\":").Append(EscapeJson(cpuName));
            sb.Append("},");

            // sys
            sb.Append("\"sys\":{");
            sb.Append("\"tempC\":").Append(FormatNum(sysTempC));
            sb.Append("},");
            // ram
            sb.Append("\"ram\":{");
            sb.Append("\"usedMB\":").Append(FormatLong(ramUsedMB)).Append(",");
            sb.Append("\"totalMB\":").Append(FormatLong(ramTotalMB)).Append(",");
            sb.Append("\"percent\":").Append(FormatNum(ramPercent));
            sb.Append("},");

            // gpu
            sb.Append("\"gpu\":{");
            sb.Append("\"percent\":").Append(FormatNum(gpuPercent)).Append(",");
            sb.Append("\"tempC\":").Append(FormatNum(gpuTempC)).Append(",");
            sb.Append("\"memUsedMB\":").Append(FormatLong(gpuMemUsedMB)).Append(",");
            sb.Append("\"memTotalMB\":").Append(FormatLong(gpuMemTotalMB)).Append(",");
            sb.Append("\"name\":").Append(EscapeJson(gpuName));
            sb.Append("},");

            // net
            sb.Append("\"net\":{");
            sb.Append("\"downBps\":").Append(FormatLong(netDownBps)).Append(",");
            sb.Append("\"upBps\":").Append(FormatLong(netUpBps));
            sb.Append("},");

            // disks
            sb.Append("\"disks\":").Append(disksJson ?? "[]").Append(",");

            // uptimeSec
            sb.Append("\"uptimeSec\":").Append(FormatLong(uptimeSec));

            sb.Append("}");
            return sb.ToString();
        }

        static string FormatNum(double? val)
        {
            if (!val.HasValue || double.IsNaN(val.Value) || double.IsInfinity(val.Value)) return "null";
            return val.Value.ToString("0.0", CultureInfo.InvariantCulture);
        }

        static string FormatLong(long? val)
        {
            if (!val.HasValue) return "null";
            return val.Value.ToString(CultureInfo.InvariantCulture);
        }

        static string EscapeJson(string s)
        {
            if (s == null) return "null";
            var sb = new StringBuilder();
            sb.Append('"');
            for (int i = 0; i < s.Length; i++)
            {
                char c = s[i];
                switch (c)
                {
                    case '\\': sb.Append("\\\\"); break;
                    case '"': sb.Append("\\\""); break;
                    case '\b': sb.Append("\\b"); break;
                    case '\f': sb.Append("\\f"); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    default:
                        if (c < 32)
                        {
                            sb.AppendFormat(CultureInfo.InvariantCulture, "\\u{0:x4}", (int)c);
                        }
                        else
                        {
                            sb.Append(c);
                        }
                        break;
                }
            }
            sb.Append('"');
            return sb.ToString();
        }

        static string GetCpuName()
        {
            try
            {
                using (var key = Registry.LocalMachine.OpenSubKey(@"HARDWARE\DESCRIPTION\System\CentralProcessor\0"))
                {
                    if (key != null)
                    {
                        var val = key.GetValue("ProcessorNameString");
                        if (val != null) return val.ToString().Trim();
                    }
                }
            }
            catch {}
            return null;
        }

        static List<PerformanceCounter> InitThermalZoneCounters()
        {
            try
            {
                if (!PerformanceCounterCategory.Exists("Thermal Zone Information")) return null;
                var cat = new PerformanceCounterCategory("Thermal Zone Information");
                string[] instances = cat.GetInstanceNames();
                if (instances == null || instances.Length == 0) return null;

                var list = new List<PerformanceCounter>();
                foreach (string inst in instances)
                {
                    try
                    {
                        var pc = new PerformanceCounter("Thermal Zone Information", "Temperature", inst, true);
                        pc.NextValue();
                        list.Add(pc);
                    }
                    catch {}
                }
                return list.Count > 0 ? list : null;
            }
            catch
            {
                return null;
            }
        }

        static double? SampleSysTemp(List<PerformanceCounter> counters)
        {
            if (counters == null || counters.Count == 0) return null;
            double? maxTemp = null;
            foreach (var c in counters)
            {
                try
                {
                    float val = c.NextValue();
                    if (val > 200.0f && val < 450.0f)
                    {
                        double cVal = Math.Round((double)val - 273.15, 1);
                        if (!maxTemp.HasValue || cVal > maxTemp.Value)
                        {
                            maxTemp = cVal;
                        }
                    }
                }
                catch {}
            }
            return maxTemp;
        }

        static double? GetCpuTemp()
        {
            // 1. WMI MSAcpi_ThermalZoneTemperature
            double? wmiTemp = GetCpuTempFromWmi();
            if (wmiTemp.HasValue) return wmiTemp;

            // 2. cputemp.txt (< 10s old)
            return GetCpuTempFromFile();
        }

        static double? GetCpuTempFromWmi()
        {
            try
            {
                using (var searcher = new ManagementObjectSearcher(@"root\wmi", "SELECT CurrentTemperature FROM MSAcpi_ThermalZoneTemperature"))
                {
                    foreach (ManagementObject obj in searcher.Get())
                    {
                        var prop = obj["CurrentTemperature"];
                        if (prop != null)
                        {
                            double kelvinTenths = Convert.ToDouble(prop);
                            double c = (kelvinTenths / 10.0) - 273.15;
                            return Math.Round(c, 1);
                        }
                    }
                }
            }
            catch {}
            return null;
        }

        static double? GetCpuTempFromFile()
        {
            try
            {
                string targetPath = Environment.GetEnvironmentVariable("PCAGENT_CPUTEMP_FILE");
                if (string.IsNullOrEmpty(targetPath))
                {
                    targetPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), @"PcAgentCpuTemp\cputemp.txt");
                }
                if (string.IsNullOrEmpty(targetPath) || !File.Exists(targetPath)) return null;

                var writeTime = File.GetLastWriteTimeUtc(targetPath);
                var age = DateTime.UtcNow - writeTime;
                if (age.TotalSeconds < -2.0 || age.TotalSeconds >= 10.0) return null;

                using (var fs = new FileStream(targetPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                using (var reader = new StreamReader(fs, Encoding.UTF8))
                {
                    string line = reader.ReadLine();
                    if (!string.IsNullOrEmpty(line))
                    {
                        double val;
                        if (double.TryParse(line.Trim(), NumberStyles.Any, CultureInfo.InvariantCulture, out val))
                        {
                            if (val > -50.0 && val < 150.0)
                            {
                                return Math.Round(val, 1);
                            }
                        }
                    }
                }
            }
            catch {}
            return null;
        }

        static string nvidiaSmiExe = null;
        static bool nvidiaSmiChecked = false;

        static string GetNvidiaSmiPath()
        {
            if (nvidiaSmiChecked) return nvidiaSmiExe;
            try
            {
                string sys32 = @"C:\Windows\System32\nvidia-smi.exe";
                if (File.Exists(sys32))
                {
                    nvidiaSmiExe = sys32;
                    nvidiaSmiChecked = true;
                    return nvidiaSmiExe;
                }
                string pathEnv = Environment.GetEnvironmentVariable("PATH");
                if (!string.IsNullOrEmpty(pathEnv))
                {
                    foreach (string part in pathEnv.Split(';'))
                    {
                        string p = part.Trim();
                        if (string.IsNullOrEmpty(p)) continue;
                        string candidate = Path.Combine(p, "nvidia-smi.exe");
                        if (File.Exists(candidate))
                        {
                            nvidiaSmiExe = candidate;
                            nvidiaSmiChecked = true;
                            return nvidiaSmiExe;
                        }
                    }
                }
            }
            catch {}
            nvidiaSmiChecked = true;
            return nvidiaSmiExe;
        }

        static NvidiaGpuInfo QueryNvidiaSmi()
        {
            string exe = GetNvidiaSmiPath();
            if (exe == null) return null;

            try
            {
                var psi = new ProcessStartInfo(exe, "--query-gpu=name,temperature.gpu,memory.used,memory.total,utilization.gpu --format=csv,noheader,nounits")
                {
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true
                };
                Process p = null;
                try
                {
                    p = Process.Start(psi);
                    if (p == null) return null;

                    var stdout = new StringBuilder();
                    p.OutputDataReceived += (s, e) =>
                    {
                        if (e.Data != null) lock (stdout) stdout.AppendLine(e.Data);
                    };
                    p.ErrorDataReceived += (s, e) => { };

                    p.BeginOutputReadLine();
                    p.BeginErrorReadLine();

                    if (!p.WaitForExit(2000))
                    {
                        try { p.Kill(); } catch {}
                        return null;
                    }
                    try { p.WaitForExit(200); } catch {}

                    string output;
                    lock (stdout) { output = stdout.ToString(); }
                    if (string.IsNullOrEmpty(output)) return null;

                    string[] lines = output.Split(new char[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
                    if (lines.Length == 0) return null;
                    string[] parts = lines[0].Split(',');
                    if (parts.Length >= 5)
                    {
                        var info = new NvidiaGpuInfo();
                        info.Name = parts[0].Trim();

                        double temp;
                        if (double.TryParse(parts[1].Trim(), NumberStyles.Any, CultureInfo.InvariantCulture, out temp))
                            info.TempC = Math.Round(temp, 1);

                        long memUsed;
                        if (long.TryParse(parts[2].Trim(), NumberStyles.Any, CultureInfo.InvariantCulture, out memUsed))
                            info.MemUsedMB = memUsed;

                        long memTotal;
                        if (long.TryParse(parts[3].Trim(), NumberStyles.Any, CultureInfo.InvariantCulture, out memTotal))
                            info.MemTotalMB = memTotal;

                        double util;
                        if (double.TryParse(parts[4].Trim(), NumberStyles.Any, CultureInfo.InvariantCulture, out util))
                            info.Percent = Math.Round(util, 1);

                        return info;
                    }
                }
                finally
                {
                    if (p != null)
                    {
                        try { p.Dispose(); } catch {}
                    }
                }
            }
            catch {}
            return null;
        }

        internal static string GetStatusText(int code)
        {
            switch (code)
            {
                case 200: return "OK";
                case 400: return "Bad Request";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                case 413: return "Payload Too Large";
                case 431: return "Request Header Fields Too Large";
                case 500: return "Internal Server Error";
                case 503: return "Service Unavailable";
                default: return "Status " + code;
            }
        }

        static void Handle(SimpleRequest req, SimpleResponse res)
        {
            string ip = (req.RemoteEndPoint != null && req.RemoteEndPoint.Address != null)
                ? req.RemoteEndPoint.Address.ToString() : "?";
            string path = req.Path ?? "/";
            Log("REQ " + req.HttpMethod + " " + path + " from " + ip);

            try
            {
                if (path == "/healthz" || path == "/")
                {
                    if (req.HttpMethod != "GET" && req.HttpMethod != "HEAD")
                    {
                        Log("  405 " + req.HttpMethod);
                        Respond(res, 405, "method_not_allowed");
                        return;
                    }
                    Log("  200 /healthz");
                    Respond(res, 200, "ok");
                    return;
                }

                if ((path == "/metrics" || path == "/quota" || path == "/exec") && !Authorize(req, res))
                {
                    return;
                }

                if (path == "/metrics")
                {
                    if (req.HttpMethod != "GET" && req.HttpMethod != "HEAD")
                    {
                        Log("  405 " + req.HttpMethod);
                        Respond(res, 405, "method_not_allowed");
                        return;
                    }
                    string json = latestMetricsJson;
                    if (string.IsNullOrEmpty(json)) json = DefaultEmptyMetricsJson;
                    RespondJson(res, 200, json);
                    return;
                }

                if (path == "/quota")
                {
                    if (req.HttpMethod != "GET" && req.HttpMethod != "HEAD")
                    {
                        Log("  405 " + req.HttpMethod);
                        Respond(res, 405, "method_not_allowed");
                        return;
                    }
                    bool refresh = false;
                    string refParam;
                    if (req.QueryString.TryGetValue("refresh", out refParam))
                    {
                        if (refParam == "1" || string.Equals(refParam, "true", StringComparison.OrdinalIgnoreCase))
                        {
                            refresh = true;
                        }
                    }
                    HandleQuota(res, refresh);
                    return;
                }

                if (path == "/exec")
                {
                    if (req.HttpMethod != "POST")
                    {
                        Log("  405 " + req.HttpMethod);
                        Respond(res, 405, "method_not_allowed");
                        return;
                    }

                    string body = req.Body ?? "";
                    string action = ExtractJson(body, "action");
                    Log("  exec action='" + action + "'");

                    string result;
                    switch (action)
                    {
                        case "sleep":
                            result = RunCmd("rundll32.exe", "powrprof.dll,SetSuspendState 0,1,0");
                            Log("  → " + result);
                            Respond(res, 200, result);
                            return;
                        case "hibernate":
                            result = RunCmd("shutdown.exe", "/h");
                            Log("  → " + result);
                            Respond(res, 200, result);
                            return;
                        case "shutdown":
                            result = RunCmd("shutdown.exe", "/s /t 0");
                            Log("  → " + result);
                            Respond(res, 200, result);
                            return;
                        case "abort":
                            result = RunCmd("shutdown.exe", "/a");
                            Log("  → " + result);
                            Respond(res, 200, result);
                            return;
                        case "ping":
                            Log("  200 ping");
                            Respond(res, 200, "pong");
                            return;
                        default:
                            Log("  400 unknown action '" + action + "'");
                            Respond(res, 400, "unknown_action");
                            return;
                    }
                }

                if (req.HttpMethod != "GET" && req.HttpMethod != "HEAD" && req.HttpMethod != "POST")
                {
                    Log("  405 " + req.HttpMethod);
                    Respond(res, 405, "method_not_allowed");
                    return;
                }

                Log("  404 " + path);
                Respond(res, 404, "not_found");
            }
            catch (Exception ex)
            {
                Log("  500 " + ex.Message);
                try { Respond(res, 500, "error: " + ex.Message); } catch {}
            }
        }

        static string RunCmd(string exe, string args)
        {
            try
            {
                var psi = new ProcessStartInfo(exe, args)
                {
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true
                };
                using (var p = Process.Start(psi))
                {
                    p.WaitForExit(8000);
                    return "ok rc=" + p.ExitCode;
                }
            }
            catch (Exception ex)
            {
                return "fail: " + ex.Message;
            }
        }

        static string ExtractJson(string json, string key)
        {
            if (string.IsNullOrEmpty(json)) return null;
            string needle = "\"" + key + "\"";
            int i = json.IndexOf(needle, StringComparison.OrdinalIgnoreCase);
            if (i < 0) return null;
            int colon = json.IndexOf(':', i + needle.Length);
            if (colon < 0) return null;
            int q1 = json.IndexOf('"', colon + 1);
            int q2 = json.IndexOf('"', q1 + 1);
            if (q1 < 0 || q2 < 0) return null;
            return json.Substring(q1 + 1, q2 - q1 - 1);
        }

        static void Respond(SimpleResponse res, int code, string msg)
        {
            byte[] buf = Encoding.UTF8.GetBytes(msg ?? "");
            res.Send(code, "text/plain; charset=utf-8", buf);
        }

        static void RespondJson(SimpleResponse res, int code, string json)
        {
            byte[] buf = Encoding.UTF8.GetBytes(json ?? "");
            res.Send(code, "application/json; charset=utf-8", buf);
        }

        static void Log(string msg)
        {
            string line = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + msg;
            Console.WriteLine(line);
            try { File.AppendAllText("pcagent.log", line + Environment.NewLine); } catch {}
        }

        static void HandleQuota(SimpleResponse res, bool refresh)
        {
            string ompPath = ResolveOmpPath();
            if (ompPath == null)
            {
                if (cachedQuotaJson != null)
                {
                    RespondJson(res, 200, InjectError(cachedQuotaJson, "omp_unavailable"));
                }
                else
                {
                    RespondJson(res, 200, "{\"error\":\"omp_unavailable\"}");
                }
                return;
            }

            DateTime now = DateTime.UtcNow;
            bool needFetch = false;

            lock (quotaLock)
            {
                if (refresh)
                {
                    if (!isFetchingQuota && (now - lastRefreshAttemptUtc).TotalSeconds >= 60)
                    {
                        needFetch = true;
                        lastRefreshAttemptUtc = now;
                    }
                }
                else if (cachedQuotaJson != null)
                {
                    if (!isFetchingQuota && (now - lastQuotaFetchUtc).TotalMinutes >= 5)
                    {
                        needFetch = true;
                    }
                }
                else
                {
                    if (!isFetchingQuota)
                    {
                        needFetch = true;
                    }
                }

                if (needFetch)
                {
                    isFetchingQuota = true;
                    ThreadPool.QueueUserWorkItem(_ => FetchQuotaWorker(ompPath));
                }
            }

            string jsonToReturn = cachedQuotaJson;
            if (jsonToReturn == null)
            {
                if (lastQuotaError != null)
                {
                    RespondJson(res, 200, "{\"error\":\"" + QuotaAggregator.EscapeJson(lastQuotaError) + "\"}");
                }
                else
                {
                    RespondJson(res, 200, "{\"loading\":true,\"providers\":[]}");
                }
            }
            else
            {
                if (lastQuotaError != null)
                {
                    RespondJson(res, 200, InjectError(jsonToReturn, lastQuotaError));
                }
                else
                {
                    RespondJson(res, 200, jsonToReturn);
                }
            }
        }

        static void FetchQuotaWorker(string ompPath)
        {
            try
            {
                var psi = new ProcessStartInfo();
                if (ompPath.EndsWith(".cmd", StringComparison.OrdinalIgnoreCase) || ompPath.EndsWith(".bat", StringComparison.OrdinalIgnoreCase))
                {
                    string comspec = Environment.GetEnvironmentVariable("ComSpec");
                    if (string.IsNullOrEmpty(comspec)) comspec = "cmd.exe";
                    psi.FileName = comspec;
                    psi.Arguments = "/c \"" + ompPath + "\" usage --json --redact";
                }
                else
                {
                    psi.FileName = ompPath;
                    psi.Arguments = "usage --json --redact";
                }
                psi.CreateNoWindow = true;
                psi.UseShellExecute = false;
                psi.RedirectStandardOutput = true;
                psi.RedirectStandardError = true;
                psi.StandardOutputEncoding = Encoding.UTF8;
                psi.StandardErrorEncoding = Encoding.UTF8;

                const int maxStdoutBytes = 512 * 1024;
                const int maxStderrBytes = 16 * 1024;
                var stdoutSb = new StringBuilder();
                var stderrSb = new StringBuilder();
                object stdoutLock = new object();
                object stderrLock = new object();
                bool stdoutExceeded = false;

                using (var p = new Process())
                {
                    p.StartInfo = psi;
                    p.OutputDataReceived += (s, e) =>
                    {
                        if (e.Data != null)
                        {
                            lock (stdoutLock)
                            {
                                if (stdoutSb.Length + e.Data.Length > maxStdoutBytes)
                                {
                                    stdoutExceeded = true;
                                    try { p.Kill(); } catch {}
                                    return;
                                }
                                stdoutSb.AppendLine(e.Data);
                            }
                        }
                    };
                    p.ErrorDataReceived += (s, e) =>
                    {
                        if (e.Data != null)
                        {
                            lock (stderrLock)
                            {
                                if (stderrSb.Length < maxStderrBytes)
                                {
                                    int remaining = maxStderrBytes - stderrSb.Length;
                                    if (e.Data.Length + 2 <= remaining)
                                    {
                                        stderrSb.AppendLine(e.Data);
                                    }
                                    else
                                    {
                                        stderrSb.Append(e.Data.Substring(0, Math.Min(e.Data.Length, remaining)));
                                    }
                                }
                            }
                        }
                    };
                    p.Start();
                    p.BeginOutputReadLine();
                    p.BeginErrorReadLine();
                    if (!p.WaitForExit(60000))
                    {
                        try { p.Kill(); } catch {}
                        lock (quotaLock)
                        {
                            lastQuotaError = "omp_unavailable";
                            isFetchingQuota = false;
                        }
                        return;
                    }
                    p.WaitForExit();
                    if (stdoutExceeded)
                    {
                        lock (quotaLock)
                        {
                            lastQuotaError = "omp_unavailable";
                            isFetchingQuota = false;
                        }
                        return;
                    }
                    if (p.ExitCode != 0)
                    {
                        lock (quotaLock)
                        {
                            lastQuotaError = "omp_unavailable";
                            isFetchingQuota = false;
                        }
                        return;
                    }
                }

                string stdout;
                lock (stdoutLock) { stdout = stdoutSb.ToString(); }
                if (stdout.Length > maxStdoutBytes)
                {
                    lock (quotaLock)
                    {
                        lastQuotaError = "omp_unavailable";
                        isFetchingQuota = false;
                    }
                    return;
                }
                long nowMs = (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
                string aggregated = QuotaAggregator.Aggregate(stdout, nowMs);

                lock (quotaLock)
                {
                    cachedQuotaJson = aggregated;
                    lastQuotaFetchUtc = DateTime.UtcNow;
                    lastQuotaError = null;
                    isFetchingQuota = false;
                }
            }
            catch (Exception ex)
            {
                Log("FetchQuotaWorker error: " + ex.Message);
                lock (quotaLock)
                {
                    lastQuotaError = "omp_unavailable";
                    isFetchingQuota = false;
                }
            }
        }

        static string ResolveOmpPath()
        {
            string envOmp = Environment.GetEnvironmentVariable("PCAGENT_OMP_PATH");
            if (!string.IsNullOrEmpty(envOmp))
            {
                envOmp = envOmp.Trim();
                if (File.Exists(envOmp)) return Path.GetFullPath(envOmp);
                string baseDir = AppDomain.CurrentDomain.BaseDirectory;
                string combined = Path.Combine(baseDir, envOmp);
                if (File.Exists(combined)) return Path.GetFullPath(combined);
                return null;
            }

            string pathEnv = Environment.GetEnvironmentVariable("PATH");
            if (!string.IsNullOrEmpty(pathEnv))
            {
                string[] dirs = pathEnv.Split(';');
                foreach (string dir in dirs)
                {
                    string trimmed = dir.Trim();
                    if (string.IsNullOrEmpty(trimmed)) continue;
                    try
                    {
                        string exe = Path.Combine(trimmed, "omp.exe");
                        if (File.Exists(exe)) return exe;
                        string cmd = Path.Combine(trimmed, "omp.cmd");
                        if (File.Exists(cmd)) return cmd;
                        string bare = Path.Combine(trimmed, "omp");
                        if (File.Exists(bare)) return bare;
                    }
                    catch {}
                }
            }

            string userProfile = Environment.GetEnvironmentVariable("USERPROFILE");
            if (!string.IsNullOrEmpty(userProfile))
            {
                try
                {
                    string bunDir = Path.Combine(userProfile, ".bun\\bin");
                    string exe = Path.Combine(bunDir, "omp.exe");
                    if (File.Exists(exe)) return exe;
                    string cmd = Path.Combine(bunDir, "omp.cmd");
                    if (File.Exists(cmd)) return cmd;
                    string bare = Path.Combine(bunDir, "omp");
                    if (File.Exists(bare)) return bare;
                }
                catch {}
            }

            return null;
        }


        static string InjectError(string json, string error)
        {
            if (string.IsNullOrEmpty(json)) return "{\"error\":\"" + QuotaAggregator.EscapeJson(error) + "\"}";
            json = json.Trim();
            if (json.StartsWith("{"))
            {
                return "{\"error\":\"" + QuotaAggregator.EscapeJson(error) + "\"," + json.Substring(1);
            }
            return "{\"error\":\"" + QuotaAggregator.EscapeJson(error) + "\"}";
        }
    }
}
