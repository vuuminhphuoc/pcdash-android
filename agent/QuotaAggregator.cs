using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace PcAgent
{
    // Turns the JSON that `omp usage --json --redact` prints into the redacted per-provider summary served on /quota.
    public static class QuotaAggregator
    {
        private static readonly Regex RedactedAccountRegex = new Regex(@"^[^@\s]{1,64}@\*$", RegexOptions.Compiled);

        public static string SanitizeField(string s, int maxLength = 64)
        {
            if (string.IsNullOrEmpty(s)) return "";
            var sb = new StringBuilder();
            for (int i = 0; i < s.Length && sb.Length < maxLength; i++)
            {
                char c = s[i];
                if (c == '@' || char.IsControl(c)) continue;
                sb.Append(c);
            }
            return sb.ToString().Trim();
        }

        public static string Aggregate(string ompJson, long nowMs)
        {
            if (string.IsNullOrEmpty(ompJson)) throw new FormatException("Empty JSON");
            object rootObj = MiniJson.Parse(ompJson);
            var root = rootObj as Dictionary<string, object>;
            if (root == null) throw new FormatException("Root is not a JSON object");

            object reportsVal;
            if (!root.TryGetValue("reports", out reportsVal) || !(reportsVal is List<object>))
            {
                throw new FormatException("Missing 'reports' array");
            }
            var reports = (List<object>)reportsVal;
            if (reports.Count > 200)
            {
                throw new FormatException("Exceeded maximum 200 reports: " + reports.Count);
            }

            long fetchedAt = nowMs;
            object genVal;
            if (root.TryGetValue("generatedAt", out genVal) && genVal != null)
            {
                try { fetchedAt = Convert.ToInt64(genVal); } catch {}
            }
            if (fetchedAt <= 0) fetchedAt = nowMs;

            var providerOrder = new List<string>();
            var providerReports = new Dictionary<string, List<Dictionary<string, object>>>(StringComparer.OrdinalIgnoreCase);

            foreach (var r in reports)
            {
                var rep = r as Dictionary<string, object>;
                if (rep == null) continue;
                string rawProvId = GetString(rep, "provider");
                string provId = SanitizeField(rawProvId, 64);
                if (string.IsNullOrEmpty(provId)) continue;
                if (string.Equals(provId, "anthropic", StringComparison.OrdinalIgnoreCase)) continue;

                if (!providerReports.ContainsKey(provId))
                {
                    providerOrder.Add(provId);
                    providerReports[provId] = new List<Dictionary<string, object>>();
                }
                providerReports[provId].Add(rep);
            }

            var sb = new StringBuilder();
            sb.Append("{\"fetchedAt\":").Append(fetchedAt).Append(",\"providers\":[");

            bool firstProv = true;
            foreach (var provId in providerOrder)
            {
                var repList = providerReports[provId];
                int totalAccounts = repList.Count;

                var groupOrder = new List<string>();
                var groupWindows = new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);
                var windowLabels = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                var windowAccounts = new Dictionary<string, List<AccountEntry>>(StringComparer.OrdinalIgnoreCase);

                for (int accIdx = 0; accIdx < repList.Count; accIdx++)
                {
                    var rep = repList[accIdx];
                    string accLabel = null;
                    object metaVal;
                    if (rep.TryGetValue("metadata", out metaVal) && metaVal is Dictionary<string, object>)
                    {
                        var meta = (Dictionary<string, object>)metaVal;
                        string email = GetString(meta, "email");
                        if (!string.IsNullOrEmpty(email))
                        {
                            email = email.Trim();
                            if (RedactedAccountRegex.IsMatch(email))
                            {
                                accLabel = email;
                            }
                        }
                    }
                    if (string.IsNullOrEmpty(accLabel))
                    {
                        accLabel = "T\u00E0i kho\u1EA3n " + (accIdx + 1);
                    }

                    object limitsVal;
                    if (!rep.TryGetValue("limits", out limitsVal) || !(limitsVal is List<object>))
                        continue;

                    var limits = (List<object>)limitsVal;
                    if (limits.Count > 100)
                    {
                        throw new FormatException("Exceeded maximum 100 limits per report: " + limits.Count);
                    }
                    var seenKeysInAccount = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

                    foreach (var limObj in limits)
                    {
                        var lim = limObj as Dictionary<string, object>;
                        if (lim == null) continue;

                        object amountVal;
                        if (!lim.TryGetValue("amount", out amountVal) || !(amountVal is Dictionary<string, object>))
                            continue;
                        var amount = (Dictionary<string, object>)amountVal;
                        object remFracVal;
                        if (!amount.TryGetValue("remainingFraction", out remFracVal) || remFracVal == null)
                            continue;

                        double fraction;
                        try { fraction = Convert.ToDouble(remFracVal, CultureInfo.InvariantCulture); }
                        catch { continue; }

                        int remPct = (int)Math.Max(0, Math.Min(100, Math.Round(fraction * 100.0, MidpointRounding.AwayFromZero)));

                        string rawGrpName = GetString(lim, "label");
                        string grpName = SanitizeField(rawGrpName, 64);
                        if (string.IsNullOrEmpty(grpName)) grpName = "General";

                        string winId = null;
                        string winLabel = null;
                        long? resetsAt = null;

                        object winVal;
                        if (lim.TryGetValue("window", out winVal) && winVal is Dictionary<string, object>)
                        {
                            var winDict = (Dictionary<string, object>)winVal;
                            winId = GetString(winDict, "id");
                            winLabel = GetString(winDict, "label");
                            object rAtVal;
                            if (winDict.TryGetValue("resetsAt", out rAtVal) && rAtVal != null)
                            {
                                try
                                {
                                    long rAt = Convert.ToInt64(rAtVal);
                                    if (rAt > 0) resetsAt = rAt;
                                }
                                catch {}
                            }
                        }
                        if (string.IsNullOrEmpty(winId))
                        {
                            object scopeVal;
                            if (lim.TryGetValue("scope", out scopeVal) && scopeVal is Dictionary<string, object>)
                            {
                                winId = GetString((Dictionary<string, object>)scopeVal, "windowId");
                            }
                        }
                        winId = SanitizeField(winId, 64);
                        if (string.IsNullOrEmpty(winId)) winId = "unknown";

                        string finalWinLabel = winLabel;
                        string normId = winId.ToLowerInvariant();
                        if (normId == "5h" || normId == "5-hour" || normId == "5 hours") finalWinLabel = "5 gi\u1EDD";
                        else if (normId == "weekly" || normId == "1w" || normId == "7d") finalWinLabel = "Tu\u1EA7n";
                        else if (normId == "30d" || normId == "30-day" || normId == "30 days") finalWinLabel = "30 ng\u00E0y";
                        else if (normId == "monthly" || normId == "month") finalWinLabel = "Th\u00E1ng";
                        else if (string.IsNullOrEmpty(finalWinLabel)) finalWinLabel = winId;

                        finalWinLabel = SanitizeField(finalWinLabel, 64);
                        if (string.IsNullOrEmpty(finalWinLabel)) finalWinLabel = winId;

                        string mapKey = grpName + "||" + winId;
                        if (seenKeysInAccount.Contains(mapKey)) continue;
                        seenKeysInAccount.Add(mapKey);

                        if (!groupWindows.ContainsKey(grpName))
                        {
                            groupOrder.Add(grpName);
                            groupWindows[grpName] = new List<string>();
                        }
                        if (!groupWindows[grpName].Contains(winId))
                        {
                            groupWindows[grpName].Add(winId);
                        }
                        windowLabels[mapKey] = finalWinLabel;

                        if (!windowAccounts.ContainsKey(mapKey))
                        {
                            windowAccounts[mapKey] = new List<AccountEntry>();
                        }
                        windowAccounts[mapKey].Add(new AccountEntry
                        {
                            Label = accLabel,
                            RemainingPct = remPct,
                            ResetsAt = resetsAt
                        });
                    }
                }

                if (groupOrder.Count == 0) continue;

                if (!firstProv) sb.Append(",");
                firstProv = false;

                string provName = SanitizeField(GetProviderName(provId), 64);
                if (string.IsNullOrEmpty(provName)) provName = provId;

                sb.Append("{\"id\":\"").Append(EscapeJson(provId)).Append("\",\"name\":\"")
                  .Append(EscapeJson(provName)).Append("\",\"groups\":[");

                bool firstGrp = true;
                foreach (var grpName in groupOrder)
                {
                    var winList = groupWindows[grpName];
                    if (winList.Count == 0) continue;

                    if (!firstGrp) sb.Append(",");
                    firstGrp = false;

                    sb.Append("{\"name\":\"").Append(EscapeJson(grpName)).Append("\",\"windows\":[");
                    bool firstWin = true;
                    foreach (var winId in winList)
                    {
                        string mapKey = grpName + "||" + winId;
                        var accList = windowAccounts[mapKey];
                        int accCount = accList.Count;
                        int pooledPct = 0;
                        if (accCount > 0)
                        {
                            double sum = 0;
                            foreach (var a in accList) sum += a.RemainingPct;
                            pooledPct = (int)Math.Max(0, Math.Min(100, Math.Round(sum / (double)accCount, MidpointRounding.AwayFromZero)));
                        }

                        long? winResetsAt = null;
                        foreach (var a in accList)
                        {
                            if (a.ResetsAt.HasValue && a.ResetsAt.Value > nowMs)
                            {
                                if (!winResetsAt.HasValue || a.ResetsAt.Value < winResetsAt.Value)
                                {
                                    winResetsAt = a.ResetsAt.Value;
                                }
                            }
                        }

                        if (!firstWin) sb.Append(",");
                        firstWin = false;

                        string wLbl = windowLabels.ContainsKey(mapKey) ? windowLabels[mapKey] : winId;
                        wLbl = SanitizeField(wLbl, 64);
                        if (string.IsNullOrEmpty(wLbl)) wLbl = winId;

                        sb.Append("{\"id\":\"").Append(EscapeJson(winId)).Append("\",\"label\":\"")
                          .Append(EscapeJson(wLbl)).Append("\",\"pooledRemainingPct\":").Append(pooledPct)
                          .Append(",\"accountsWithQuota\":").Append(accCount)
                          .Append(",\"accountsTotal\":").Append(totalAccounts)
                          .Append(",\"resetsAt\":").Append(winResetsAt.HasValue ? winResetsAt.Value.ToString() : "null")
                          .Append(",\"accounts\":[");

                        bool firstAcc = true;
                        foreach (var a in accList)
                        {
                            if (!firstAcc) sb.Append(",");
                            firstAcc = false;
                            sb.Append("{\"label\":\"").Append(EscapeJson(a.Label))
                              .Append("\",\"remainingPct\":").Append(a.RemainingPct)
                              .Append(",\"resetsAt\":").Append(a.ResetsAt.HasValue ? a.ResetsAt.Value.ToString() : "null")
                              .Append("}");
                        }
                        sb.Append("]}");
                    }
                    sb.Append("]}");
                }
                sb.Append("]}");
            }

            sb.Append("]}");
            string result = sb.ToString();

            var finalSb = new StringBuilder(result.Length);
            for (int i = 0; i < result.Length; i++)
            {
                char c = result[i];
                if (c == '@')
                {
                    if (i + 1 < result.Length && result[i + 1] == '*')
                    {
                        finalSb.Append("@*");
                        i++;
                    }
                    else
                    {
                        continue;
                    }
                }
                else
                {
                    finalSb.Append(c);
                }
            }

            return finalSb.ToString();
        }

        public static string EscapeJson(string s)
        {
            if (s == null) return "";
            var sb = new StringBuilder();
            for (int i = 0; i < s.Length; i++)
            {
                char c = s[i];
                switch (c)
                {
                    case '"': sb.Append("\\\""); break;
                    case '\\': sb.Append("\\\\"); break;
                    case '\b': sb.Append("\\b"); break;
                    case '\f': sb.Append("\\f"); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    default:
                        if (c < ' ')
                            sb.AppendFormat("\\u{0:x4}", (int)c);
                        else
                            sb.Append(c);
                        break;
                }
            }
            return sb.ToString();
        }

        private static string GetString(Dictionary<string, object> dict, string key)
        {
            object val;
            if (dict.TryGetValue(key, out val) && val != null) return val.ToString();
            return null;
        }

        private static string GetProviderName(string id)
        {
            if (string.IsNullOrEmpty(id)) return "";
            switch (id.ToLowerInvariant())
            {
                case "google-antigravity": return "Antigravity";
                case "openai-codex": return "Codex";
                case "github-copilot": return "Copilot";
                case "xai-oauth": return "Grok";
                case "anthropic": return "Claude";
                case "ollama-cloud": return "Ollama Cloud";
                default:
                    string[] parts = id.Split('-', '_');
                    for (int i = 0; i < parts.Length; i++)
                    {
                        if (parts[i].Length > 0)
                            parts[i] = char.ToUpperInvariant(parts[i][0]) + parts[i].Substring(1);
                    }
                    return string.Join(" ", parts);
            }
        }

        private class AccountEntry
        {
            public string Label;
            public int RemainingPct;
            public long? ResetsAt;
        }

        private class MiniJson
        {
            private readonly string _s;
            private int _pos;

            private MiniJson(string s)
            {
                _s = s ?? "";
                _pos = 0;
            }
            private const int MaxDepth = 32;

            public static object Parse(string s)
            {
                if (s == null) throw new FormatException("JSON string is null");
                var parser = new MiniJson(s);
                parser.SkipWhitespace();
                if (parser._pos >= parser._s.Length) throw new FormatException("Empty JSON");
                object val = parser.ParseValue(0);
                parser.SkipWhitespace();
                if (parser._pos < parser._s.Length) throw new FormatException("Trailing characters after JSON value");
                return val;
            }

            private void SkipWhitespace()
            {
                while (_pos < _s.Length)
                {
                    char c = _s[_pos];
                    if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\uFEFF')
                    {
                        _pos++;
                    }
                    else
                    {
                        break;
                    }
                }
            }

            private object ParseValue(int depth)
            {
                if (depth > MaxDepth) throw new FormatException("Max JSON depth exceeded (32)");
                SkipWhitespace();
                if (_pos >= _s.Length) throw new FormatException("Unexpected EOF");
                char c = _s[_pos];
                if (c == '{') return ParseObject(depth);
                if (c == '[') return ParseArray(depth);
                if (c == '"') return ParseString();
                if (c == 't' || c == 'f') return ParseBool();
                if (c == 'n') return ParseNull();
                if (c == '-' || (c >= '0' && c <= '9')) return ParseNumber();
                throw new FormatException("Unexpected character '" + c + "' at position " + _pos);
            }

            private Dictionary<string, object> ParseObject(int depth)
            {
                _pos++;
                var dict = new Dictionary<string, object>(StringComparer.OrdinalIgnoreCase);
                SkipWhitespace();
                if (_pos < _s.Length && _s[_pos] == '}')
                {
                    _pos++;
                    return dict;
                }

                while (_pos < _s.Length)
                {
                    SkipWhitespace();
                    if (_pos >= _s.Length || _s[_pos] != '"') throw new FormatException("Expected string key in object at " + _pos);
                    string key = ParseString();
                    SkipWhitespace();
                    if (_pos >= _s.Length || _s[_pos] != ':') throw new FormatException("Expected ':' after key at " + _pos);
                    _pos++;
                    object val = ParseValue(depth + 1);
                    dict[key] = val;
                    SkipWhitespace();
                    if (_pos < _s.Length && _s[_pos] == ',')
                    {
                        _pos++;
                        continue;
                    }
                    if (_pos < _s.Length && _s[_pos] == '}')
                    {
                        _pos++;
                        return dict;
                    }
                    throw new FormatException("Expected ',' or '}' in object at " + _pos);
                }
                throw new FormatException("Unterminated object");
            }

            private List<object> ParseArray(int depth)
            {
                _pos++;
                var list = new List<object>();
                SkipWhitespace();
                if (_pos < _s.Length && _s[_pos] == ']')
                {
                    _pos++;
                    return list;
                }

                while (_pos < _s.Length)
                {
                    object val = ParseValue(depth + 1);
                    list.Add(val);
                    SkipWhitespace();
                    if (_pos < _s.Length && _s[_pos] == ',')
                    {
                        _pos++;
                        continue;
                    }
                    if (_pos < _s.Length && _s[_pos] == ']')
                    {
                        _pos++;
                        return list;
                    }
                    throw new FormatException("Expected ',' or ']' in array at " + _pos);
                }
                throw new FormatException("Unterminated array");
            }

            private string ParseString()
            {
                _pos++;
                var sb = new StringBuilder();
                while (_pos < _s.Length)
                {
                    char c = _s[_pos++];
                    if (c == '"') return sb.ToString();
                    if (c == '\\')
                    {
                        if (_pos >= _s.Length) throw new FormatException("Unterminated escape sequence in string");
                        char esc = _s[_pos++];
                        switch (esc)
                        {
                            case '"': sb.Append('"'); break;
                            case '\\': sb.Append('\\'); break;
                            case '/': sb.Append('/'); break;
                            case 'b': sb.Append('\b'); break;
                            case 'f': sb.Append('\f'); break;
                            case 'n': sb.Append('\n'); break;
                            case 'r': sb.Append('\r'); break;
                            case 't': sb.Append('\t'); break;
                            case 'u':
                                if (_pos + 4 > _s.Length) throw new FormatException("Incomplete unicode escape in string");
                                string hex = _s.Substring(_pos, 4);
                                _pos += 4;
                                sb.Append((char)int.Parse(hex, NumberStyles.HexNumber));
                                break;
                            default:
                                sb.Append(esc);
                                break;
                        }
                    }
                    else
                    {
                        sb.Append(c);
                    }
                }
                throw new FormatException("Unterminated string");
            }

            private object ParseNumber()
            {
                int start = _pos;
                if (_pos < _s.Length && _s[_pos] == '-') _pos++;
                while (_pos < _s.Length && (_s[_pos] >= '0' && _s[_pos] <= '9')) _pos++;
                bool isFloat = false;
                if (_pos < _s.Length && _s[_pos] == '.')
                {
                    isFloat = true;
                    _pos++;
                    while (_pos < _s.Length && (_s[_pos] >= '0' && _s[_pos] <= '9')) _pos++;
                }
                if (_pos < _s.Length && (_s[_pos] == 'e' || _s[_pos] == 'E'))
                {
                    isFloat = true;
                    _pos++;
                    if (_pos < _s.Length && (_s[_pos] == '+' || _s[_pos] == '-')) _pos++;
                    while (_pos < _s.Length && (_s[_pos] >= '0' && _s[_pos] <= '9')) _pos++;
                }
                string numStr = _s.Substring(start, _pos - start);
                if (isFloat)
                {
                    double d;
                    if (double.TryParse(numStr, NumberStyles.Float, CultureInfo.InvariantCulture, out d))
                        return d;
                }
                else
                {
                    long l;
                    if (long.TryParse(numStr, NumberStyles.Integer, CultureInfo.InvariantCulture, out l))
                        return l;
                    double d;
                    if (double.TryParse(numStr, NumberStyles.Float, CultureInfo.InvariantCulture, out d))
                        return d;
                }
                throw new FormatException("Invalid number: " + numStr);
            }

            private bool ParseBool()
            {
                if (_pos + 4 <= _s.Length && _s.Substring(_pos, 4) == "true")
                {
                    _pos += 4;
                    return true;
                }
                if (_pos + 5 <= _s.Length && _s.Substring(_pos, 5) == "false")
                {
                    _pos += 5;
                    return false;
                }
                throw new FormatException("Invalid boolean at " + _pos);
            }

            private object ParseNull()
            {
                if (_pos + 4 <= _s.Length && _s.Substring(_pos, 4) == "null")
                {
                    _pos += 4;
                    return null;
                }
                throw new FormatException("Invalid null at " + _pos);
            }
        }
    }
}
