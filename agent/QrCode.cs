using System;
using System.Collections.Generic;

namespace PcAgent
{
    // Minimal QR Code (ISO/IEC 18004) encoder: byte mode, error correction level M, versions 1-10.
    // Enough for the short PCDash setup payload; no third-party code on the PC.
    public static class QrEncoder
    {
        const int MaxVersion = 10;
        // Index = version. Level M only.
        static readonly int[] EccPerBlock = { -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26 };
        static readonly int[] NumBlocks = { -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5 };

        // Returns modules[y, x]; true = dark. Does not include the quiet zone.
        public static bool[,] Encode(byte[] data)
        {
            if (data == null) throw new ArgumentNullException("data");
            int version = -1;
            int capacityBits = 0;
            for (int v = 1; v <= MaxVersion; v++)
            {
                int cap = DataCodewords(v) * 8;
                int used = 4 + (v < 10 ? 8 : 16) + data.Length * 8;
                if (used <= cap)
                {
                    version = v;
                    capacityBits = cap;
                    break;
                }
            }
            if (version < 0) throw new ArgumentException("QR payload too long");

            List<bool> bits = new List<bool>();
            AppendBits(bits, 0x4, 4);
            AppendBits(bits, data.Length, version < 10 ? 8 : 16);
            foreach (byte b in data) AppendBits(bits, b, 8);
            AppendBits(bits, 0, Math.Min(4, capacityBits - bits.Count));
            AppendBits(bits, 0, (8 - bits.Count % 8) % 8);
            for (int pad = 0xEC; bits.Count < capacityBits; pad ^= 0xEC ^ 0x11)
            {
                AppendBits(bits, pad, 8);
            }

            byte[] dataWords = new byte[bits.Count / 8];
            for (int i = 0; i < bits.Count; i++)
            {
                if (bits[i]) dataWords[i >> 3] |= (byte)(1 << (7 - (i & 7)));
            }
            return Build(version, Interleave(dataWords, version));
        }

        static void AppendBits(List<bool> bits, int value, int count)
        {
            for (int i = count - 1; i >= 0; i--) bits.Add(((value >> i) & 1) != 0);
        }

        static int RawModules(int ver)
        {
            int result = (16 * ver + 128) * ver + 64;
            if (ver >= 2)
            {
                int numAlign = ver / 7 + 2;
                result -= (25 * numAlign - 10) * numAlign - 55;
                if (ver >= 7) result -= 36;
            }
            return result;
        }

        static int DataCodewords(int ver)
        {
            return RawModules(ver) / 8 - EccPerBlock[ver] * NumBlocks[ver];
        }

        static byte[] Interleave(byte[] data, int ver)
        {
            int numBlocks = NumBlocks[ver];
            int eccLen = EccPerBlock[ver];
            int rawCodewords = RawModules(ver) / 8;
            int numShort = numBlocks - rawCodewords % numBlocks;
            int shortLen = rawCodewords / numBlocks;

            byte[] divisor = ReedSolomonDivisor(eccLen);
            List<byte[]> blocks = new List<byte[]>();
            for (int i = 0, k = 0; i < numBlocks; i++)
            {
                int datLen = shortLen - eccLen + (i < numShort ? 0 : 1);
                byte[] dat = new byte[datLen];
                Array.Copy(data, k, dat, 0, datLen);
                k += datLen;
                byte[] ecc = ReedSolomonRemainder(dat, divisor);
                byte[] block = new byte[shortLen + 1];
                Array.Copy(dat, 0, block, 0, datLen);
                // Short blocks keep one unused slot before the ECC bytes (skipped when interleaving).
                Array.Copy(ecc, 0, block, shortLen + 1 - eccLen, eccLen);
                blocks.Add(block);
            }

            byte[] result = new byte[rawCodewords];
            int r = 0;
            for (int i = 0; i < shortLen + 1; i++)
            {
                for (int j = 0; j < blocks.Count; j++)
                {
                    if (i != shortLen - eccLen || j >= numShort)
                    {
                        result[r++] = blocks[j][i];
                    }
                }
            }
            if (r != rawCodewords) throw new InvalidOperationException("QR interleave size mismatch");
            return result;
        }

        static byte[] ReedSolomonDivisor(int degree)
        {
            byte[] result = new byte[degree];
            result[degree - 1] = 1;
            int root = 1;
            for (int i = 0; i < degree; i++)
            {
                for (int j = 0; j < degree; j++)
                {
                    result[j] = (byte)GfMultiply(result[j], root);
                    if (j + 1 < degree) result[j] ^= result[j + 1];
                }
                root = GfMultiply(root, 0x02);
            }
            return result;
        }

        static byte[] ReedSolomonRemainder(byte[] data, byte[] divisor)
        {
            byte[] result = new byte[divisor.Length];
            foreach (byte b in data)
            {
                int factor = b ^ result[0];
                Array.Copy(result, 1, result, 0, result.Length - 1);
                result[result.Length - 1] = 0;
                for (int i = 0; i < result.Length; i++)
                {
                    result[i] ^= (byte)GfMultiply(divisor[i], factor);
                }
            }
            return result;
        }

        static int GfMultiply(int x, int y)
        {
            int z = 0;
            for (int i = 7; i >= 0; i--)
            {
                z = (z << 1) ^ ((z >> 7) * 0x11D);
                z ^= ((y >> i) & 1) * x;
            }
            return z;
        }

        static bool[,] Build(int version, byte[] codewords)
        {
            int size = version * 4 + 17;
            bool[,] m = new bool[size, size];
            bool[,] fn = new bool[size, size];

            DrawFunctionPatterns(m, fn, version, size);
            DrawCodewords(m, fn, codewords, size);

            int bestMask = 0;
            int bestPenalty = int.MaxValue;
            for (int mask = 0; mask < 8; mask++)
            {
                ApplyMask(m, fn, mask, size);
                DrawFormatBits(m, fn, mask, size);
                int penalty = Penalty(m, size);
                if (penalty < bestPenalty)
                {
                    bestPenalty = penalty;
                    bestMask = mask;
                }
                ApplyMask(m, fn, mask, size);
            }
            ApplyMask(m, fn, bestMask, size);
            DrawFormatBits(m, fn, bestMask, size);
            return m;
        }

        static void SetFn(bool[,] m, bool[,] fn, int x, int y, bool dark)
        {
            m[y, x] = dark;
            fn[y, x] = true;
        }

        static void DrawFunctionPatterns(bool[,] m, bool[,] fn, int version, int size)
        {
            for (int i = 0; i < size; i++)
            {
                SetFn(m, fn, 6, i, i % 2 == 0);
                SetFn(m, fn, i, 6, i % 2 == 0);
            }
            DrawFinder(m, fn, 3, 3, size);
            DrawFinder(m, fn, size - 4, 3, size);
            DrawFinder(m, fn, 3, size - 4, size);

            int[] pos = AlignmentPositions(version, size);
            int n = pos.Length;
            for (int i = 0; i < n; i++)
            {
                for (int j = 0; j < n; j++)
                {
                    if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                    DrawAlignment(m, fn, pos[i], pos[j]);
                }
            }

            DrawFormatBits(m, fn, 0, size);
            if (version >= 7) DrawVersion(m, fn, version, size);
        }

        static int[] AlignmentPositions(int version, int size)
        {
            if (version == 1) return new int[0];
            int numAlign = version / 7 + 2;
            int step = (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
            List<int> result = new List<int>();
            for (int i = 0, pos = size - 7; i < numAlign - 1; i++, pos -= step)
            {
                result.Insert(0, pos);
            }
            result.Insert(0, 6);
            return result.ToArray();
        }

        static void DrawFinder(bool[,] m, bool[,] fn, int cx, int cy, int size)
        {
            for (int dy = -4; dy <= 4; dy++)
            {
                for (int dx = -4; dx <= 4; dx++)
                {
                    int dist = Math.Max(Math.Abs(dx), Math.Abs(dy));
                    int x = cx + dx;
                    int y = cy + dy;
                    if (x >= 0 && x < size && y >= 0 && y < size)
                    {
                        SetFn(m, fn, x, y, dist != 2 && dist != 4);
                    }
                }
            }
        }

        static void DrawAlignment(bool[,] m, bool[,] fn, int cx, int cy)
        {
            for (int dy = -2; dy <= 2; dy++)
            {
                for (int dx = -2; dx <= 2; dx++)
                {
                    SetFn(m, fn, cx + dx, cy + dy, Math.Max(Math.Abs(dx), Math.Abs(dy)) != 1);
                }
            }
        }

        static void DrawFormatBits(bool[,] m, bool[,] fn, int mask, int size)
        {
            // Error correction level M has format indicator 0.
            int data = mask;
            int rem = data;
            for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >> 9) * 0x537);
            int bits = ((data << 10) | rem) ^ 0x5412;

            for (int i = 0; i <= 5; i++) SetFn(m, fn, 8, i, GetBit(bits, i));
            SetFn(m, fn, 8, 7, GetBit(bits, 6));
            SetFn(m, fn, 8, 8, GetBit(bits, 7));
            SetFn(m, fn, 7, 8, GetBit(bits, 8));
            for (int i = 9; i < 15; i++) SetFn(m, fn, 14 - i, 8, GetBit(bits, i));

            for (int i = 0; i < 8; i++) SetFn(m, fn, size - 1 - i, 8, GetBit(bits, i));
            for (int i = 8; i < 15; i++) SetFn(m, fn, 8, size - 15 + i, GetBit(bits, i));
            SetFn(m, fn, 8, size - 8, true);
        }

        static void DrawVersion(bool[,] m, bool[,] fn, int version, int size)
        {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >> 11) * 0x1F25);
            int bits = (version << 12) | rem;
            for (int i = 0; i < 18; i++)
            {
                bool bit = GetBit(bits, i);
                int a = size - 11 + i % 3;
                int b = i / 3;
                SetFn(m, fn, a, b, bit);
                SetFn(m, fn, b, a, bit);
            }
        }

        static bool GetBit(int x, int i)
        {
            return ((x >> i) & 1) != 0;
        }

        static void DrawCodewords(bool[,] m, bool[,] fn, byte[] data, int size)
        {
            int i = 0;
            for (int right = size - 1; right >= 1; right -= 2)
            {
                if (right == 6) right = 5;
                for (int vert = 0; vert < size; vert++)
                {
                    for (int j = 0; j < 2; j++)
                    {
                        int x = right - j;
                        bool upward = ((right + 1) & 2) == 0;
                        int y = upward ? size - 1 - vert : vert;
                        if (!fn[y, x] && i < data.Length * 8)
                        {
                            m[y, x] = GetBit(data[i >> 3], 7 - (i & 7));
                            i++;
                        }
                    }
                }
            }
            if (i != data.Length * 8) throw new InvalidOperationException("QR codeword placement mismatch");
        }

        static void ApplyMask(bool[,] m, bool[,] fn, int mask, int size)
        {
            for (int y = 0; y < size; y++)
            {
                for (int x = 0; x < size; x++)
                {
                    bool invert;
                    switch (mask)
                    {
                        case 0: invert = (x + y) % 2 == 0; break;
                        case 1: invert = y % 2 == 0; break;
                        case 2: invert = x % 3 == 0; break;
                        case 3: invert = (x + y) % 3 == 0; break;
                        case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                        case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                        case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                        default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                    }
                    if (!fn[y, x] && invert) m[y, x] = !m[y, x];
                }
            }
        }

        static readonly bool[] FinderLikeA = { true, false, true, true, true, false, true, false, false, false, false };
        static readonly bool[] FinderLikeB = { false, false, false, false, true, false, true, true, true, false, true };

        static int Penalty(bool[,] m, int size)
        {
            int result = 0;

            // Rule 1: runs of 5 or more same-colored modules in a row or column.
            for (int pass = 0; pass < 2; pass++)
            {
                for (int a = 0; a < size; a++)
                {
                    int run = 1;
                    for (int b = 1; b < size; b++)
                    {
                        bool cur = pass == 0 ? m[a, b] : m[b, a];
                        bool prev = pass == 0 ? m[a, b - 1] : m[b - 1, a];
                        if (cur == prev)
                        {
                            run++;
                        }
                        else
                        {
                            if (run >= 5) result += run - 2;
                            run = 1;
                        }
                    }
                    if (run >= 5) result += run - 2;
                }
            }

            // Rule 2: 2x2 blocks of one color.
            for (int y = 0; y < size - 1; y++)
            {
                for (int x = 0; x < size - 1; x++)
                {
                    bool c = m[y, x];
                    if (c == m[y, x + 1] && c == m[y + 1, x] && c == m[y + 1, x + 1]) result += 3;
                }
            }

            // Rule 3: finder-like 1:1:3:1:1 patterns with four light modules on one side.
            for (int pass = 0; pass < 2; pass++)
            {
                for (int a = 0; a < size; a++)
                {
                    for (int start = 0; start + 11 <= size; start++)
                    {
                        bool matchA = true;
                        bool matchB = true;
                        for (int k = 0; k < 11 && (matchA || matchB); k++)
                        {
                            bool v = pass == 0 ? m[a, start + k] : m[start + k, a];
                            if (v != FinderLikeA[k]) matchA = false;
                            if (v != FinderLikeB[k]) matchB = false;
                        }
                        if (matchA) result += 40;
                        if (matchB) result += 40;
                    }
                }
            }

            // Rule 4: balance of dark and light modules.
            int dark = 0;
            for (int y = 0; y < size; y++)
            {
                for (int x = 0; x < size; x++)
                {
                    if (m[y, x]) dark++;
                }
            }
            int total = size * size;
            int k4 = (Math.Abs(dark * 20 - total * 10) + total - 1) / total - 1;
            result += k4 * 10;
            return result;
        }
    }
}
