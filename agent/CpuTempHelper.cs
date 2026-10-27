using System;
using System.Globalization;
using System.IO;
using System.Security.Principal;
using System.Text;
using System.Threading;
using LibreHardwareMonitor.Hardware;

namespace PcAgent
{
    class CpuTempHelper
    {
        static volatile bool running = true;

        static bool IsAdministrator()
        {
            try
            {
                using (var identity = WindowsIdentity.GetCurrent())
                {
                    var principal = new WindowsPrincipal(identity);
                    return principal.IsInRole(WindowsBuiltInRole.Administrator);
                }
            }
            catch
            {
                return false;
            }
        }

        static int Main(string[] args)
        {
            if (!IsAdministrator())
            {
                Console.WriteLine("CpuTempHelper yeu cau quyen Administrator.");
                return 0;
            }

            Console.CancelKeyPress += delegate(object sender, ConsoleCancelEventArgs e)
            {
                running = false;
                e.Cancel = true;
            };

            Computer computer = null;
            try
            {
                computer = new Computer
                {
                    IsCpuEnabled = true,
                    IsGpuEnabled = false,
                    IsMemoryEnabled = false,
                    IsMotherboardEnabled = false,
                    IsControllerEnabled = false,
                    IsNetworkEnabled = false,
                    IsStorageEnabled = false
                };
                computer.Open();
            }
            catch (Exception ex)
            {
                Console.WriteLine("Loi khoi dong LibreHardwareMonitor: " + ex.Message);
                return 0;
            }

            // Chay quyen cao: duong ra co dinh trong thu muc chi admin ghi duoc, khong doc bien moi truong.
            string targetFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), @"PcAgentCpuTemp\cputemp.txt");

            try
            {
                string dir = Path.GetDirectoryName(targetFile);
                if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
                {
                    Directory.CreateDirectory(dir);
                }
            }
            catch {}

            string tempFile = targetFile + ".tmp";

            double? initialTemp = ReadCpuTemp(computer);
            int initTries = 0;
            while (!initialTemp.HasValue && initTries < 4 && running)
            {
                Thread.Sleep(500);
                initTries++;
                initialTemp = ReadCpuTemp(computer);
            }

            if (!initialTemp.HasValue)
            {
                Console.WriteLine("Khong the doc cam bien nhiet do CPU.");
                try { computer.Close(); } catch {}
                return 0;
            }

            WriteAtomic(targetFile, tempFile, initialTemp.Value);

            int consecutiveErrors = 0;
            while (running)
            {
                for (int i = 0; i < 20 && running; i++)
                {
                    Thread.Sleep(100);
                }
                if (!running) break;

                try
                {
                    double? tempVal = ReadCpuTemp(computer);
                    if (tempVal.HasValue)
                    {
                        consecutiveErrors = 0;
                        WriteAtomic(targetFile, tempFile, tempVal.Value);
                    }
                    else
                    {
                        consecutiveErrors++;
                        if (consecutiveErrors >= 5)
                        {
                            Console.WriteLine("Lien tiep khong doc duoc nhiet do, ket thuc.");
                            break;
                        }
                    }
                }
                catch
                {
                    consecutiveErrors++;
                    if (consecutiveErrors >= 5) break;
                }
            }

            try
            {
                computer.Close();
            }
            catch {}

            return 0;
        }

        static double? ReadCpuTemp(Computer computer)
        {
            if (computer == null) return null;
            ISensor packageSensor = null;
            ISensor fallbackSensor = null;

            foreach (var hw in computer.Hardware)
            {
                if (hw.HardwareType == HardwareType.Cpu)
                {
                    try { hw.Update(); } catch {}

                    if (hw.SubHardware != null)
                    {
                        foreach (var sub in hw.SubHardware)
                        {
                            try { sub.Update(); } catch {}
                        }
                    }

                    if (hw.Sensors != null)
                    {
                        foreach (var s in hw.Sensors)
                        {
                            if (s.SensorType == SensorType.Temperature && s.Value.HasValue)
                            {
                                string name = s.Name ?? "";
                                if (name.IndexOf("Package", StringComparison.OrdinalIgnoreCase) >= 0)
                                {
                                    packageSensor = s;
                                    break;
                                }
                                if (fallbackSensor == null)
                                {
                                    fallbackSensor = s;
                                }
                            }
                        }
                    }

                    if (packageSensor != null) break;
                }
            }

            ISensor chosen = packageSensor != null ? packageSensor : fallbackSensor;
            if (chosen != null && chosen.Value.HasValue)
            {
                double v = (double)chosen.Value.Value;
                if (v > -50.0 && v < 150.0)
                {
                    return Math.Round(v, 1);
                }
            }
            return null;
        }

        static void WriteAtomic(string targetPath, string tempPath, double val)
        {
            string line = val.ToString("0.0", CultureInfo.InvariantCulture) + "\n";
            File.WriteAllText(tempPath, line, Encoding.UTF8);

            try
            {
                if (File.Exists(targetPath))
                {
                    File.Replace(tempPath, targetPath, null);
                }
                else
                {
                    File.Move(tempPath, targetPath);
                }
            }
            catch
            {
                try
                {
                    if (File.Exists(targetPath))
                    {
                        File.Delete(targetPath);
                    }
                    File.Move(tempPath, targetPath);
                }
                catch {}
            }
        }
    }
}
