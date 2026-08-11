using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace ImlLauncher
{
    internal static class HealthProbe
    {
        /// <summary>
        /// Vite binds to host "localhost" (often ::1 only on Windows). Also may use 5173..5192.
        /// Fast path: short TCP probes first, then one HTTP confirm — avoids stalling the poller.
        /// </summary>
        public static bool FrontendReady(int preferredPort, int portSpan, out string detail)
        {
            detail = "";
            if (preferredPort <= 0)
            {
                preferredPort = 5173;
            }
            if (portSpan < 1)
            {
                portSpan = 1;
            }

            // Preferred port: try localhost / IPv6 / IPv4 (Vite --host localhost).
            if (TryFrontendPort(preferredPort, new string[] { "localhost", "::1", "127.0.0.1" }, out detail))
            {
                return true;
            }

            // Alternate ports (front-end/scripts/dev.mjs findAvailablePort) — localhost only.
            for (int offset = 1; offset < portSpan; offset++)
            {
                if (TryFrontendPort(preferredPort + offset, new string[] { "localhost", "::1" }, out detail))
                {
                    return true;
                }
            }

            detail = "нет ответа :" + preferredPort + "…" + (preferredPort + portSpan - 1);
            return false;
        }

        private static bool TryFrontendPort(int port, string[] hosts, out string detail)
        {
            detail = "";
            for (int h = 0; h < hosts.Length; h++)
            {
                string host = hosts[h];
                string tcpDetail;
                if (!TcpOpen(host, port, 120, out tcpDetail))
                {
                    continue;
                }

                string url = BuildHttpUrl(host, port);
                string httpDetail;
                if (HttpOk(url, 400, out httpDetail))
                {
                    detail = url + " (" + httpDetail + ")";
                    return true;
                }

                detail = host + ":" + port + " (" + tcpDetail + ")";
                return true;
            }
            return false;
        }

        public static string BuildHttpUrl(string host, int port)
        {
            if (host != null && host.IndexOf(':') >= 0 && host[0] != '[')
            {
                return "http://[" + host + "]:" + port + "/";
            }
            return "http://" + host + ":" + port + "/";
        }

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
                // Avoid stale KeepAlive against a just-bound Vite socket.
                req.KeepAlive = false;
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
            if (string.IsNullOrEmpty(host))
            {
                detail = "empty host";
                return false;
            }
            try
            {
                // Prefer explicit address family for ::1 / 127.0.0.1; Dns for "localhost".
                IPAddress addr;
                if (IPAddress.TryParse(host, out addr))
                {
                    using (TcpClient client = new TcpClient(addr.AddressFamily))
                    {
                        IAsyncResult ar = client.BeginConnect(addr, port, null, null);
                        bool ok = ar.AsyncWaitHandle.WaitOne(timeoutMs, false);
                        if (!ok)
                        {
                            try { client.Close(); } catch { }
                            detail = "timeout";
                            return false;
                        }
                        client.EndConnect(ar);
                        detail = "tcp " + port;
                        return true;
                    }
                }

                using (TcpClient client = new TcpClient())
                {
                    IAsyncResult ar = client.BeginConnect(host, port, null, null);
                    bool ok = ar.AsyncWaitHandle.WaitOne(timeoutMs, false);
                    if (!ok)
                    {
                        try { client.Close(); } catch { }
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
