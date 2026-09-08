using System.Windows;

namespace VProxies;

public partial class ActivationWindow : Window
{
    private readonly LicenseService _licenseService;

    public ActivationWindow(LicenseService licenseService, string? initialMessage = null)
    {
        InitializeComponent();
        _licenseService = licenseService;
        StatusText.Text = initialMessage ?? "";
        EmailBox.Text = licenseService.CurrentLicense?.Email ?? "";
        if (string.IsNullOrWhiteSpace(EmailBox.Text)) EmailBox.Focus(); else LicenseKeyBox.Focus();
    }

    private void Activate_Click(object sender, RoutedEventArgs e)
    {
        var status = _licenseService.Activate(EmailBox.Text, LicenseKeyBox.Text);
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
