using System.IO;
using System.Security.Cryptography;
using System.Text.Json;

namespace VProxies;

public sealed record LicensePayload
{
    public int Version { get; init; }
    public string Product { get; init; } = "";
    public string Email { get; init; } = "";
    public long IssuedAt { get; init; }
    public long? ExpiresAt { get; init; }
    public string LicenseId { get; init; } = "";
}

public sealed record StoredLicense
{
    public string Email { get; init; } = "";
    public string Key { get; init; } = "";
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

    public LicensePayload? CurrentLicense { get; private set; }

    public LicenseStatus LoadInstalledLicense()
    {
        try
        {
            if (!File.Exists(_licensePath)) return new(false, "A license key is required.");
            var protectedLicense = File.ReadAllText(_licensePath).Trim();
            var json = SecretStore.Unprotect(protectedLicense);
            var stored = JsonSerializer.Deserialize<StoredLicense>(json);
            if (stored is null || string.IsNullOrWhiteSpace(stored.Email) || string.IsNullOrWhiteSpace(stored.Key))
                return new(false, "The saved license cannot be opened by this Windows user.");
            return Validate(stored.Email, stored.Key);
        }
        catch (Exception ex)
        {
            return new(false, "The saved license is damaged: " + ex.Message);
        }
    }

    public LicenseStatus Activate(string email, string key)
    {
        var status = Validate(email, key);
        if (!status.IsValid) return status;

        Directory.CreateDirectory(Path.GetDirectoryName(_licensePath)!);
        var temporary = _licensePath + ".tmp";
        var stored = JsonSerializer.Serialize(new StoredLicense { Email = NormalizeEmail(email), Key = NormalizeKey(key) });
        File.WriteAllText(temporary, SecretStore.Protect(stored));
        File.Move(temporary, _licensePath, true);
        return status;
    }

    public LicenseStatus Validate(string email, string key)
    {
        try
        {
            var normalizedEmail = NormalizeEmail(email);
            if (!IsValidEmail(normalizedEmail)) return new(false, "Enter the email address used for the order.");
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
            if (!NormalizeEmail(license.Email).Equals(normalizedEmail, StringComparison.Ordinal))
                return new(false, "The email address does not match this license key.");

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
        var expiry = license.ExpiresAt is { } value
            ? DateTimeOffset.FromUnixTimeSeconds(value).ToLocalTime().ToString("yyyy-MM-dd")
            : "Lifetime";
        return $"{license.Email} · {expiry}";
    }

    private static string NormalizeKey(string value) => string.Concat((value ?? "").Where(c => !char.IsWhiteSpace(c)));
    private static string NormalizeEmail(string value) => (value ?? "").Trim().ToLowerInvariant();
    private static bool IsValidEmail(string value)
    {
        try { return new System.Net.Mail.MailAddress(value).Address.Equals(value, StringComparison.OrdinalIgnoreCase); }
        catch { return false; }
    }

    public static byte[] Base64UrlDecode(string value)
    {
        var padded = value.Replace('-', '+').Replace('_', '/');
        padded += new string('=', (4 - padded.Length % 4) % 4);
        return Convert.FromBase64String(padded);
    }
}
