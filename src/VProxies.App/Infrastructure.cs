using System.Diagnostics;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace VProxies;

public static class SecretStore
{
    [StructLayout(LayoutKind.Sequential)] private struct Blob { public int Size; public IntPtr Data; }
    [DllImport("crypt32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CryptProtectData(ref Blob input, string? description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("crypt32.dll", SetLastError = true)]
    private static extern bool CryptUnprotectData(ref Blob input, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);

    public static string Protect(string value) => string.IsNullOrEmpty(value) ? "" : Convert.ToBase64String(Transform(Encoding.UTF8.GetBytes(value), true));
    public static string Unprotect(string value)
    {
        if (string.IsNullOrWhiteSpace(value)) return "";
        try { return Encoding.UTF8.GetString(Transform(Convert.FromBase64String(value), false)); }
        catch { return ""; }
    }

    private static byte[] Transform(byte[] input, bool protect)
    {
        var inBlob = new Blob { Size = input.Length, Data = Marshal.AllocHGlobal(input.Length) };
        try
        {
            Marshal.Copy(input, 0, inBlob.Data, input.Length);
            Blob output;
            var ok = protect
                ? CryptProtectData(ref inBlob, "VProxies SA proxy credential", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output)
                : CryptUnprotectData(ref inBlob, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output);
            if (!ok) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            try { var bytes = new byte[output.Size]; Marshal.Copy(output.Data, bytes, 0, output.Size); return bytes; }
            finally { LocalFree(output.Data); }
        }
        finally { CryptographicOperations.ZeroMemory(input); Marshal.FreeHGlobal(inBlob.Data); }
    }
}

public sealed class SettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private readonly string _path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VProxiesSA", "settings.json");

    public StoredSettings Load()
    {
        try { return JsonSerializer.Deserialize<StoredSettings>(File.ReadAllText(_path)) ?? new(); }
        catch { return new(); }
    }

    public void Save(StoredSettings settings)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        var temporary = _path + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(settings, JsonOptions));
        File.Move(temporary, _path, true);
    }
}

public static class ProxyTester
{
    public static async Task<TimeSpan> TestAsync(ProxySettings proxy, CancellationToken cancellationToken = default)
    {
        Validate(proxy);
        var watch = Stopwatch.StartNew();
        using var tcp = new TcpClient();
        await tcp.ConnectAsync(proxy.Host, proxy.Port, cancellationToken);
        await using var networkStream = tcp.GetStream();
        Stream stream = networkStream;
        SslStream? tlsStream = null;
        if (proxy.Protocol == ProxyProtocol.HTTPS)
        {
            tlsStream = new SslStream(networkStream, leaveInnerStreamOpen: true);
            await tlsStream.AuthenticateAsClientAsync(new SslClientAuthenticationOptions { TargetHost = string.IsNullOrWhiteSpace(proxy.Sni) ? proxy.Host : proxy.Sni }, cancellationToken);
            stream = tlsStream;
        }
        await using var ownedTlsStream = tlsStream;
        if (proxy.Protocol is ProxyProtocol.HTTP or ProxyProtocol.HTTPS)
        {
            var auth = string.IsNullOrEmpty(proxy.Username) ? "" : $"Proxy-Authorization: Basic {Convert.ToBase64String(Encoding.UTF8.GetBytes(proxy.Username + ":" + proxy.Password))}\r\n";
            var request = Encoding.ASCII.GetBytes($"CONNECT www.cloudflare.com:443 HTTP/1.1\r\nHost: www.cloudflare.com:443\r\n{auth}Connection: close\r\n\r\n");
            await stream.WriteAsync(request, cancellationToken);
            var buffer = new byte[256]; var count = await stream.ReadAsync(buffer, cancellationToken);
            var status = Encoding.ASCII.GetString(buffer, 0, count);
            if (!status.Contains(" 200 ")) throw new IOException("HTTP CONNECT was rejected: " + status.Split('\n')[0].Trim());
        }
        else if (proxy.Protocol == ProxyProtocol.SOCKS5)
        {
            await stream.WriteAsync(string.IsNullOrEmpty(proxy.Username) ? new byte[] { 5, 1, 0 } : new byte[] { 5, 1, 2 }, cancellationToken);
            var hello = new byte[2]; await ReadExact(stream, hello, cancellationToken);
            if (hello[1] == 2)
            {
                var user = Encoding.UTF8.GetBytes(proxy.Username); var password = Encoding.UTF8.GetBytes(proxy.Password);
                if (user.Length > 255 || password.Length > 255) throw new ArgumentException("SOCKS5 credentials are too long.");
                var packet = new byte[3 + user.Length + password.Length]; packet[0] = 1; packet[1] = (byte)user.Length; user.CopyTo(packet, 2); packet[2 + user.Length] = (byte)password.Length; password.CopyTo(packet, 3 + user.Length);
                await stream.WriteAsync(packet, cancellationToken); var reply = new byte[2]; await ReadExact(stream, reply, cancellationToken);
                if (reply[1] != 0) throw new IOException("SOCKS5 authentication failed.");
            }
            else if (hello[1] != 0) throw new IOException("SOCKS5 authentication method was rejected.");
            await SendSocksConnect(stream, cancellationToken);
        }
        else
        {
            var host = Encoding.ASCII.GetBytes("www.cloudflare.com"); var user = Encoding.UTF8.GetBytes(proxy.Username);
            var packet = new byte[9 + user.Length + host.Length + 1]; packet[0] = 4; packet[1] = 1; packet[2] = 1; packet[3] = 187; packet[7] = 1; user.CopyTo(packet, 8); packet[8 + user.Length] = 0; host.CopyTo(packet, 9 + user.Length);
            await stream.WriteAsync(packet, cancellationToken); var response = new byte[8]; await ReadExact(stream, response, cancellationToken);
            if (response[1] != 90) throw new IOException($"SOCKS4A connect failed ({response[1]}).");
        }
        watch.Stop(); return watch.Elapsed;
    }

    private static async Task SendSocksConnect(Stream stream, CancellationToken cancellationToken)
    {
        var host = Encoding.ASCII.GetBytes("www.cloudflare.com"); var packet = new byte[7 + host.Length]; packet[0] = 5; packet[1] = 1; packet[3] = 3; packet[4] = (byte)host.Length; host.CopyTo(packet, 5); packet[^2] = 1; packet[^1] = 187;
        await stream.WriteAsync(packet, cancellationToken); var response = new byte[4]; await ReadExact(stream, response, cancellationToken);
        if (response[1] != 0) throw new IOException($"SOCKS5 connect failed ({response[1]}).");
    }

    private static void Validate(ProxySettings proxy)
    {
        if (string.IsNullOrWhiteSpace(proxy.Host)) throw new ArgumentException("Enter a proxy host or IP address.");
        if (proxy.Port is < 1 or > 65535) throw new ArgumentException("Proxy port must be between 1 and 65535.");
    }

    private static async Task ReadExact(Stream stream, byte[] buffer, CancellationToken cancellationToken)
    {
        var offset = 0;
        while (offset < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(offset), cancellationToken);
            if (read == 0) throw new EndOfStreamException();
            offset += read;
        }
    }
}
