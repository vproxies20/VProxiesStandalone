using System.Windows;

namespace VProxies;

public partial class ActivationWindow : Window
{
    private readonly LicenseService _licenseService;

    public ActivationWindow(LicenseService licenseService, string? initialMessage = null)
    {
        InitializeComponent();
        _licenseService = licenseService;
        DeviceIdBox.Text = licenseService.DeviceId;
        StatusText.Text = initialMessage ?? "";
        LicenseKeyBox.Focus();
    }

    private void CopyDeviceId_Click(object sender, RoutedEventArgs e)
    {
        Clipboard.SetText(_licenseService.DeviceId);
        StatusText.Foreground = (System.Windows.Media.Brush)FindResource("SuccessBrush");
        StatusText.Text = "Device ID copied.";
    }

    private void Activate_Click(object sender, RoutedEventArgs e)
    {
        var status = _licenseService.Activate(LicenseKeyBox.Text);
        StatusText.Foreground = (System.Windows.Media.Brush)FindResource(status.IsValid ? "SuccessBrush" : "DangerBrush");
        StatusText.Text = status.Message;
        if (!status.IsValid) return;
        DialogResult = true;
        Close();
    }

    private void Exit_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }
}
