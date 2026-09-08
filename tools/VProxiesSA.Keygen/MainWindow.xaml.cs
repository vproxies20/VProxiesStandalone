using Microsoft.Win32;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Windows;

namespace VProxiesSA.Keygen;

public partial class MainWindow : Window
{
    private const string KeyPrefix = "VPSA1";
    private const string ProductId = "VProxiesSA";
    private static readonly Regex DevicePattern = new("^[A-F0-9]{32}$", RegexOptions.Compiled | RegexOptions.IgnoreCase);
    private readonly string _privateKeyPath = Path.Combine(AppContext.BaseDirectory, "VProxiesSA-private-key.pem");

    public MainWindow()
    {
        InitializeComponent();
        ExpirationPicker.SelectedDate = DateTime.Today.AddYears(1);
        Lifetime_Changed(this, new RoutedEventArgs());
        if (!File.Exists(_privateKeyPath))
            SetStatus("Private key not found beside this tool. License generation is disabled.", false);
    }

    private void Lifetime_Changed(object sender, RoutedEventArgs e)
    {
        if (ExpirationPicker is not null) ExpirationPicker.IsEnabled = LifetimeBox.IsChecked != true;
    }

    private void Generate_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (!File.Exists(_privateKeyPath)) throw new FileNotFoundException("VProxiesSA-private-key.pem was not found beside the generator.");
            var device = NormalizeDeviceId(DeviceIdBox.Text);
            if (!DevicePattern.IsMatch(device)) throw new ArgumentException("Enter the 32-character device ID shown by VProxies SA.");

            long? expiresAt = null;
            if (LifetimeBox.IsChecked != true)
            {
                var expiry = ExpirationPicker.SelectedDate ?? throw new ArgumentException("Choose an expiration date.");
                if (expiry.Date < DateTime.Today) throw new ArgumentException("The expiration date cannot be in the past.");
                expiresAt = new DateTimeOffset(expiry.Date.AddDays(1).AddTicks(-1), TimeZoneInfo.Local.GetUtcOffset(expiry.Date)).ToUnixTimeSeconds();
            }

            var payload = new LicensePayload
            {
                Version = 1,
                Product = ProductId,
                DeviceId = device,
                Customer = CustomerBox.Text.Trim(),
                IssuedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                ExpiresAt = expiresAt,
                LicenseId = Guid.NewGuid().ToString("N")
            };
            var payloadBytes = JsonSerializer.SerializeToUtf8Bytes(payload);
            using var signer = ECDsa.Create();
            signer.ImportFromPem(File.ReadAllText(_privateKeyPath));
            var signature = signer.SignData(payloadBytes, HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
            LicenseKeyBox.Text = $"{KeyPrefix}.{Base64Url(payloadBytes)}.{Base64Url(signature)}";
            SetStatus("License generated successfully.", true);
        }
        catch (Exception ex) { SetStatus(ex.Message, false); }
    }

    private void CopyKey_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(LicenseKeyBox.Text)) { SetStatus("Generate a license first.", false); return; }
        System.Windows.Clipboard.SetText(LicenseKeyBox.Text);
        SetStatus("License key copied.", true);
    }

    private void SaveKey_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(LicenseKeyBox.Text)) { SetStatus("Generate a license first.", false); return; }
        var dialog = new SaveFileDialog { Filter = "VProxies SA license (*.txt)|*.txt", FileName = $"VProxiesSA-license-{DateTime.Now:yyyyMMdd-HHmm}.txt", DefaultExt = ".txt" };
        if (dialog.ShowDialog(this) != true) return;
        File.WriteAllText(dialog.FileName, LicenseKeyBox.Text, Encoding.UTF8);
        SetStatus("License saved.", true);
    }

    private void SetStatus(string message, bool success)
    {
        StatusText.Text = message;
        StatusText.Foreground = new System.Windows.Media.SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(success ? "#34D399" : "#F87171"));
    }

    private static string NormalizeDeviceId(string value) => Regex.Replace(value ?? "", "[^A-Fa-f0-9]", "").ToUpperInvariant();
    private static string Base64Url(byte[] value) => Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private sealed record LicensePayload
    {
        public int Version { get; init; }
        public string Product { get; init; } = "";
        public string DeviceId { get; init; } = "";
        public string Customer { get; init; } = "";
        public long IssuedAt { get; init; }
        public long? ExpiresAt { get; init; }
        public string LicenseId { get; init; } = "";
    }
}
