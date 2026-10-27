using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Text;
using PcAgent;

// Test driver: renders QrEncoder output for assorted payload sizes to PNG + the expected text,
// so scripts/test-qr-roundtrip.ps1 can decode the PNGs with ZXing and compare.
static class QrDump
{
    static int Main(string[] args)
    {
        string outDir = args[0];
        Directory.CreateDirectory(outDir);
        int[] lengths = { 1, 14, 15, 40, 84, 85, 100, 150, 200, 213 };
        Random rnd = new Random(1234);
        foreach (int n in lengths)
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.Append((char)(33 + rnd.Next(94)));
            Save(outDir, "len" + n, sb.ToString());
        }
        Save(outDir, "payload", "pcdash://setup?v=1&ip=192.168.1.50&mac=AA:BB:CC:DD:EE:FF&port=8799&token=0123456789abcdef0123456789abcdef");

        try
        {
            QrEncoder.Encode(new byte[214]);
            Console.Error.WriteLine("expected 214 bytes to be rejected");
            return 1;
        }
        catch (ArgumentException) { }
        return 0;
    }

    static void Save(string dir, string name, string text)
    {
        bool[,] m = QrEncoder.Encode(Encoding.ASCII.GetBytes(text));
        int size = m.GetLength(0);
        int scale = 6, quiet = 4;
        int px = (size + quiet * 2) * scale;
        using (Bitmap bmp = new Bitmap(px, px))
        {
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.Clear(Color.White);
                for (int y = 0; y < size; y++)
                    for (int x = 0; x < size; x++)
                        if (m[y, x]) g.FillRectangle(Brushes.Black, (x + quiet) * scale, (y + quiet) * scale, scale, scale);
            }
            bmp.Save(Path.Combine(dir, name + ".png"), ImageFormat.Png);
        }
        File.WriteAllText(Path.Combine(dir, name + ".txt"), text, new UTF8Encoding(false));
    }
}
