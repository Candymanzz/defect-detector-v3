using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace ImlLauncher
{
    internal static class HealthProbe
    {
        public static bool HttpOk(string url, int timeoutMs, out string detail)
        {
            detail = "";
            try
            {
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create(url);
                req.Method = "GET";
                req.Timeout = timeoutMs;
                req.ReadWriteTimeout = timeoutMs;
                req.Proxy = null;
                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                {
                    int code = (int)resp.StatusCode;
                    detail = "HTTP " + code;
                    return code >= 200 && code < 500;
                }
            }
            catch (WebException ex)
            {
                if (ex.Response != null)
                {
                    HttpWebResponse resp = ex.Response as HttpWebResponse;
                    if (resp != null)
                    {
                        int code = (int)resp.StatusCode;
                        detail = "HTTP " + code;
                        // Any HTTP response means the listener is up.
                        return true;
                    }
                }
                detail = ShortMessage(ex.Message);
                return false;
            }
            catch (Exception ex)
            {
                detail = ShortMessage(ex.Message);
                return false;
            }
        }

        public static bool HttpBodyContains(string url, int timeoutMs, string expected, out string detail)
        {
            detail = "";
            try
            {
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create(url);
                req.Method = "GET";
                req.Timeout = timeoutMs;
                req.ReadWriteTimeout = timeoutMs;
                req.Proxy = null;
                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                using (Stream stream = resp.GetResponseStream())
                using (StreamReader reader = new StreamReader(stream ?? Stream.Null, Encoding.UTF8))
                {
                    string body = reader.ReadToEnd();
                    detail = "HTTP " + (int)resp.StatusCode;
                    if (string.IsNullOrEmpty(expected))
                    {
                        return resp.StatusCode == HttpStatusCode.OK;
                    }
                    return body != null && body.IndexOf(expected, StringComparison.OrdinalIgnoreCase) >= 0;
                }
            }
            catch (Exception ex)
            {
                detail = ShortMessage(ex.Message);
                return false;
            }
        }

        public static bool TcpOpen(string host, int port, int timeoutMs, out string detail)
        {
            detail = "";
            try
            {
                using (TcpClient client = new TcpClient())
                {
                    IAsyncResult ar = client.BeginConnect(host, port, null, null);
                    bool ok = ar.AsyncWaitHandle.WaitOne(timeoutMs, false);
                    if (!ok)
                    {
                        detail = "timeout";
                        return false;
                    }
                    client.EndConnect(ar);
                    detail = "tcp " + port;
                    return true;
                }
            }
            catch (Exception ex)
            {
                detail = ShortMessage(ex.Message);
                return false;
            }
        }

        private static string ShortMessage(string message)
        {
            if (string.IsNullOrEmpty(message))
            {
                return "ошибка";
            }
            message = message.Replace("\r", " ").Replace("\n", " ");
            if (message.Length > 80)
            {
                return message.Substring(0, 77) + "…";
            }
            return message;
        }
    }
}
