using System.IO;

namespace VProxies;

public enum ProxyProtocol { HTTP, HTTPS, SOCKS4, SOCKS5 }

public sealed record ProxySettings
{
    public ProxyProtocol Protocol { get; init; } = ProxyProtocol.SOCKS5;
    public string Host { get; init; } = "";
    public int Port { get; init; }
    public string Username { get; init; } = "";
    public string Password { get; init; } = "";
    public string Sni { get; init; } = "";
}

public sealed record LocalProxy
{
    public string Id { get; init; } = Guid.NewGuid().ToString("N");
    public string Name { get; init; } = "Proxy";
    public ProxyProtocol Protocol { get; init; } = ProxyProtocol.SOCKS5;
    public string Host { get; init; } = "";
    public int Port { get; init; }
    public string Username { get; init; } = "";
    public string Password { get; init; } = "";
    public string Sni { get; init; } = "";
    public string EndpointText => $"{Host}:{Port}";
    public string ProtocolText => Protocol.ToString();
    public string AuthenticationText => string.IsNullOrWhiteSpace(Username) ? "None" : "Username/password";
    public string OutboundTag => "proxy-" + Id;
    public ProxySettings ToSettings() => new() { Protocol = Protocol, Host = Host, Port = Port, Username = Username, Password = Password, Sni = Sni };
}

public sealed record ApplicationRule
{
    public string Id { get; init; } = Guid.NewGuid().ToString("N");
    public string ApplicationPath { get; init; } = "";
    public string Target { get; init; } = "direct";
    public string ApplicationName => Path.GetFileName(ApplicationPath);
    public string LocationText => Path.GetDirectoryName(ApplicationPath) ?? "";
    public string TargetText { get; init; } = "Direct";
}

public sealed record RouteTarget
{
    public string Id { get; init; } = "direct";
    public string Name { get; init; } = "Direct (this PC IP)";
}

public sealed record RoutingSettings
{
    public IReadOnlyList<LocalProxy> Proxies { get; init; } = [];
    public IReadOnlyList<ApplicationRule> Rules { get; init; } = [];
    public string DefaultTarget { get; init; } = "direct";
    public bool StrictRoute { get; init; } = true;
    public bool RemoteDns { get; init; }
}

public sealed record StoredProxy
{
    public string Id { get; init; } = "";
    public string Name { get; init; } = "Proxy";
    public ProxyProtocol Protocol { get; init; } = ProxyProtocol.SOCKS5;
    public string Host { get; init; } = "";
    public int Port { get; init; }
    public string Username { get; init; } = "";
    public string ProtectedPassword { get; init; } = "";
    public string Sni { get; init; } = "";
}

public sealed record StoredApplicationRule
{
    public string Id { get; init; } = "";
    public string ApplicationPath { get; init; } = "";
    public string Target { get; init; } = "direct";
}

public sealed record StoredSettings
{
    public List<StoredProxy> Proxies { get; init; } = [];
    public List<StoredApplicationRule> Rules { get; init; } = [];
    public string DefaultTarget { get; init; } = "direct";
    public bool RemoteDns { get; init; }
    public bool StrictRoute { get; init; } = true;
    public bool CloseToTray { get; init; } = true;
}
