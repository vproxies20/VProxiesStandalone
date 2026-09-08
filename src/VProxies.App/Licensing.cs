using Microsoft.Win32;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace VProxies;

public sealed record LicensePayload
{
    public int Version { get; init; }
    public string Product { get; init; } = "";
    public string DeviceId { get; init; } = "";
    public string Customer { get; init; } = "";
    public long IssuedAt { get; init; }
    public long? ExpiresAt { get; init; }
    public string LicenseId { get; init; } = "";
}

public sealed record LicenseStatus(bool IsValid, string Message, LicensePayload? License = null);

public sealed class LicenseService
{
    public const string KeyPrefix = "VPSA1";
    private const string ProductId = "VProxiesSA";
    private const string PublicKeyPem = """
        -----BEGIN PUBLIC KEY-----
        MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEPE0gh3tx1cVeogazOr6/3LwlTwU2
        WGIqMsavaEziQQRb/L2bl75REMVgiH8g+YnICrso2PKWwZuLcIVHHResnQ==
        -----END PUBLIC KEY-----
        """;

    private readonly string _licensePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "VProxiesSA", "license.dat");

    public string DeviceId { get; } = CreateDeviceId();
    public LicensePayload? CurrentLicense { get; private set; }

    public LicenseStatus LoadInstalledLicense()
    {
        try
        {
            if (!File.Exists(_licensePath)) return new(false, "A license key is required.");
            var protectedKey = File.ReadAllText(_licensePath).Trim();
            var key = SecretStore.Unprotect(protectedKey);
            if (string.IsNullOrWhiteSpace(key)) return new(false, "The saved license cannot be opened by this Windows user.");
            return Validate(key);
        }
        catch (Exception ex)
        {
            return new(false, "The saved license is damaged: " + ex.Message);
        }
    }

    public LicenseStatus Activate(string key)
    {
        var status = Validate(key);
        if (!status.IsValid) return status;

        Directory.CreateDirectory(Path.GetDirectoryName(_licensePath)!);
        var temporary = _licensePath + ".tmp";
        File.WriteAllText(temporary, SecretStore.Protect(NormalizeKey(key)));
        File.Move(temporary, _licensePath, true);
        return status;
    }

    public LicenseStatus Validate(string key)
    {
        try
        {
            var normalized = NormalizeKey(key);
            var parts = normalized.Split('.');
            if (parts.Length != 3 || !parts[0].Equals(KeyPrefix, StringComparison.Ordinal))
                return new(false, "This is not a VProxies SA license key.");

            var payloadBytes = Base64UrlDecode(parts[1]);
            var signature = Base64UrlDecode(parts[2]);
            using var verifier = ECDsa.Create();
            verifier.ImportFromPem(PublicKeyPem);
            if (!verifier.VerifyData(payloadBytes, signature, HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation))
                return new(false, "The license signature is invalid.");

            var license = JsonSerializer.Deserialize<LicensePayload>(payloadBytes)
                ?? throw new InvalidDataException("The license data is empty.");
            if (license.Version != 1 || !license.Product.Equals(ProductId, StringComparison.Ordinal))
                return new(false, "This license is for a different product or version.");
            if (!NormalizeDeviceId(license.DeviceId).Equals(NormalizeDeviceId(DeviceId), StringComparison.OrdinalIgnoreCase))
                return new(false, "This license belongs to another computer.");

            var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            if (license.IssuedAt > now + 24 * 60 * 60)
                return new(false, "The computer date is earlier than the license issue date.");
            if (license.ExpiresAt is { } expiresAt && now > expiresAt)
                return new(false, $"This license expired on {DateTimeOffset.FromUnixTimeSeconds(expiresAt).ToLocalTime():yyyy-MM-dd}.");

            CurrentLicense = license;
            return new(true, "License activated successfully.", license);
        }
        catch (FormatException)
        {
            return new(false, "The license key format is invalid.");
        }
        catch (CryptographicException)
        {
            return new(false, "The license key could not be verified.");
        }
        catch (Exception ex)
        {
            return new(false, "The license key is invalid: " + ex.Message);
        }
    }

    public string DescribeCurrentLicense()
    {
        if (CurrentLicense is not { } license) return "No active license";
        var owner = string.IsNullOrWhiteSpace(license.Customer) ? "Licensed device" : license.Customer;
        var expiry = license.ExpiresAt is { } value
            ? DateTimeOffset.FromUnixTimeSeconds(value).ToLocalTime().ToString("yyyy-MM-dd")
            : "Lifetime";
        return $"{owner} · {expiry}";
    }

    private static string CreateDeviceId()
    {
        string machineGuid;
        try
        {
            machineGuid = Registry.GetValue(@"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Cryptography", "MachineGuid", null)?.ToString() ?? "";
        }
        catch { machineGuid = ""; }

        if (string.IsNullOrWhiteSpace(machineGuid))
            machineGuid = $"{Environment.MachineName}|{Environment.OSVersion.VersionString}";

        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(ProductId + "|" + machineGuid.Trim().ToUpperInvariant()));
        return string.Join('-', Convert.ToHexString(hash.AsSpan(0, 16)).Chunk(4).Select(chars => new string(chars)));
    }

    private static string NormalizeKey(string value) => string.Concat((value ?? "").Where(c => !char.IsWhiteSpace(c)));
    private static string NormalizeDeviceId(string value) => (value ?? "").Replace("-", "", StringComparison.Ordinal).Trim();

    public static byte[] Base64UrlDecode(string value)
    {
        var padded = value.Replace('-', '+').Replace('_', '/');
        padded += new string('=', (4 - padded.Length % 4) % 4);
        return Convert.FromBase64String(padded);
    }
}
