using System.ComponentModel;
using System.IO;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using Microsoft.Win32;
using MediaColor = System.Windows.Media.Color;
using MediaColorConverter = System.Windows.Media.ColorConverter;

namespace VProxies;

public partial class MainWindow : Window
{
    private readonly SettingsStore _store = new();
    private readonly SingBoxCore _core = new();
    private readonly TrayIcon _trayIcon = new();
    private readonly List<LocalProxy> _proxies = [];
    private readonly List<ApplicationRule> _rules = [];
    private readonly HashSet<string> _sensitiveLogValues = new(StringComparer.OrdinalIgnoreCase);
    private string? _editingProxyId;
    private string? _editingRuleId;
    private bool _loadingSettings;
    private bool _exitRequested;
    private bool _shutdownInProgress;

    public MainWindow()
    {
        InitializeComponent();
        _core.Log += AppendLog;
        _trayIcon.ShowRequested += RestoreFromTray;
        _trayIcon.ExitRequested += async () => await ExitApplicationAsync(confirm: true);
        StateChanged += MainWindow_StateChanged;
        Closing += MainWindow_Closing;
        LoadSettings();
        AppendLog("VProxies 1.1.0 ready. Local routing only; no account or API connection.");
    }

    private void LoadSettings()
    {
        _loadingSettings = true;
        try
        {
            var settings = _store.Load();
            _proxies.Clear();
            _proxies.AddRange(settings.Proxies.Select(x => new LocalProxy
            {
                Id = string.IsNullOrWhiteSpace(x.Id) ? Guid.NewGuid().ToString("N") : x.Id,
                Name = x.Name,
                Protocol = x.Protocol,
                Host = x.Host,
                Port = x.Port,
                Username = x.Username,
                Password = SecretStore.Unprotect(x.ProtectedPassword),
                Sni = x.Sni
            }));
            _rules.Clear();
            _rules.AddRange(settings.Rules.Where(x => !string.IsNullOrWhiteSpace(x.ApplicationPath)).Select(x => CreateRule(
                string.IsNullOrWhiteSpace(x.Id) ? Guid.NewGuid().ToString("N") : x.Id,
                x.ApplicationPath,
                TargetExists(x.Target) ? x.Target : "direct")));
            StrictRouteBox.IsChecked = settings.StrictRoute;
            RemoteDnsBox.IsChecked = settings.RemoteDns;
            CloseToTrayBox.IsChecked = settings.CloseToTray;
            RefreshProxyGrid();
            RefreshTargets(TargetExists(settings.DefaultTarget) ? settings.DefaultTarget : "direct", "direct");
            NewProxyForm();
            NewRuleForm();
        }
        finally { _loadingSettings = false; }
    }

    private void SaveSettings()
    {
        if (_loadingSettings) return;
        _store.Save(new StoredSettings
        {
            Proxies = _proxies.Select(x => new StoredProxy
            {
                Id = x.Id, Name = x.Name, Protocol = x.Protocol, Host = x.Host, Port = x.Port,
                Username = x.Username, ProtectedPassword = SecretStore.Protect(x.Password), Sni = x.Sni
            }).ToList(),
            Rules = _rules.Select(x => new StoredApplicationRule { Id = x.Id, ApplicationPath = x.ApplicationPath, Target = x.Target }).ToList(),
            DefaultTarget = DefaultTargetBox.SelectedValue as string ?? "direct",
            StrictRoute = StrictRouteBox.IsChecked == true,
            RemoteDns = RemoteDnsBox.IsChecked == true,
            CloseToTray = CloseToTrayBox.IsChecked == true
        });
    }

    private void RefreshProxyGrid()
    {
        ProxyGrid.ItemsSource = null;
        ProxyGrid.ItemsSource = _proxies;
    }

    private void RefreshRuleGrid()
    {
        for (var index = 0; index < _rules.Count; index++)
            _rules[index] = CreateRule(_rules[index].Id, _rules[index].ApplicationPath, TargetExists(_rules[index].Target) ? _rules[index].Target : "direct");
        RuleGrid.ItemsSource = null;
        RuleGrid.ItemsSource = _rules;
    }

    private void RefreshTargets(string? defaultSelection = null, string? ruleSelection = null)
    {
        defaultSelection ??= DefaultTargetBox.SelectedValue as string ?? "direct";
        ruleSelection ??= RuleTargetBox.SelectedValue as string ?? "direct";
        var targets = BuildTargets();
        DefaultTargetBox.ItemsSource = targets;
        RuleTargetBox.ItemsSource = targets;
        DefaultTargetBox.SelectedValue = targets.Any(x => x.Id == defaultSelection) ? defaultSelection : "direct";
        RuleTargetBox.SelectedValue = targets.Any(x => x.Id == ruleSelection) ? ruleSelection : "direct";
        RefreshRuleGrid();
    }

    private List<RouteTarget> BuildTargets()
    {
        var targets = new List<RouteTarget>
        {
            new() { Id = "direct", Name = "Direct (this PC IP)" },
            new() { Id = "block", Name = "Block connection" }
        };
        targets.AddRange(_proxies.Select(x => new RouteTarget { Id = x.Id, Name = $"{x.Name} · {x.Protocol}" }));
        return targets;
    }

    private bool TargetExists(string target) => target is "direct" or "block" || _proxies.Any(x => x.Id.Equals(target, StringComparison.OrdinalIgnoreCase));

    private ApplicationRule CreateRule(string id, string path, string target)
    {
        var name = target switch
        {
            "direct" => "Direct (this PC IP)",
            "block" => "Block connection",
            _ => _proxies.FirstOrDefault(x => x.Id.Equals(target, StringComparison.OrdinalIgnoreCase)) is { } proxy ? $"{proxy.Name} · {proxy.Protocol}" : "Direct (this PC IP)"
        };
        return new ApplicationRule { Id = id, ApplicationPath = path, Target = TargetExists(target) ? target : "direct", TargetText = name };
    }

    private void NewProxy_Click(object sender, RoutedEventArgs e) => NewProxyForm();

    private void NewProxyForm()
    {
        _editingProxyId = null;
        ProxyGrid.SelectedItem = null;
        ProxyNameBox.Text = $"Proxy {_proxies.Count + 1}";
        ProtocolBox.SelectedIndex = 3;
        HostBox.Clear();
        PortBox.Text = "1080";
        ProxyUsernameBox.Clear();
        ProxyPasswordBox.Clear();
        SniBox.Clear();
        SaveProxyButton.Content = "ADD PROXY";
        DeleteProxyButton.IsEnabled = false;
    }

    private void ProxyGrid_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ProxyGrid.SelectedItem is not LocalProxy proxy) return;
        _editingProxyId = proxy.Id;
        ProxyNameBox.Text = proxy.Name;
        ProtocolBox.SelectedIndex = (int)proxy.Protocol;
        HostBox.Text = proxy.Host;
        PortBox.Text = proxy.Port.ToString();
        ProxyUsernameBox.Text = proxy.Username;
        ProxyPasswordBox.Password = proxy.Password;
        SniBox.Text = proxy.Sni;
        SaveProxyButton.Content = "UPDATE PROXY";
        DeleteProxyButton.IsEnabled = true;
    }

    private void SaveProxy_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var proxy = ReadProxyForm(_editingProxyId ?? Guid.NewGuid().ToString("N"));
            if (_proxies.Any(x => !x.Id.Equals(proxy.Id, StringComparison.OrdinalIgnoreCase) && x.Name.Equals(proxy.Name, StringComparison.OrdinalIgnoreCase)))
                throw new ArgumentException("Each proxy must have a unique name.");
            var index = _proxies.FindIndex(x => x.Id == proxy.Id);
            if (index >= 0) _proxies[index] = proxy; else _proxies.Add(proxy);
            RefreshProxyGrid();
            RefreshTargets(ruleSelection: proxy.Id);
            SaveSettings();
            ProxyGrid.SelectedItem = proxy;
            AppendLog($"Saved {proxy.Name} ({proxy.Protocol} {proxy.EndpointText}).");
        }
        catch (Exception ex) { ShowError(ex); }
    }

    private LocalProxy ReadProxyForm(string id)
    {
        if (string.IsNullOrWhiteSpace(ProxyNameBox.Text)) throw new ArgumentException("Enter a proxy name.");
        if (string.IsNullOrWhiteSpace(HostBox.Text)) throw new ArgumentException("Enter a proxy host or IP address.");
        if (!int.TryParse(PortBox.Text, out var port) || port is < 1 or > 65535) throw new ArgumentException("Proxy port must be between 1 and 65535.");
        if (ProtocolBox.SelectedIndex is < 0 or > 3) throw new ArgumentException("Select a proxy protocol.");
        return new LocalProxy
        {
            Id = id, Name = ProxyNameBox.Text.Trim(), Protocol = (ProxyProtocol)ProtocolBox.SelectedIndex,
            Host = HostBox.Text.Trim(), Port = port, Username = ProxyUsernameBox.Text.Trim(),
            Password = ProxyPasswordBox.Password, Sni = SniBox.Text.Trim()
        };
    }

    private async void TestProxy_Click(object sender, RoutedEventArgs e)
    {
        TestProxyButton.IsEnabled = false;
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
            var elapsed = await ProxyTester.TestAsync(ReadProxyForm(_editingProxyId ?? Guid.NewGuid().ToString("N")).ToSettings(), timeout.Token);
            AppendLog($"Proxy check succeeded in {elapsed.TotalMilliseconds:0} ms.");
            System.Windows.MessageBox.Show(this, $"Proxy connection succeeded in {elapsed.TotalMilliseconds:0} ms.", "VProxies Proxy Check", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex) { ShowError(ex); }
        finally { TestProxyButton.IsEnabled = true; }
    }

    private void DeleteProxy_Click(object sender, RoutedEventArgs e)
    {
        if (_editingProxyId is null || _proxies.FirstOrDefault(x => x.Id == _editingProxyId) is not { } proxy) return;
        var usedBy = _rules.Count(x => x.Target == proxy.Id) + ((DefaultTargetBox.SelectedValue as string) == proxy.Id ? 1 : 0);
        var detail = usedBy > 0 ? $"\n\n{usedBy} route(s) use this proxy and will be changed to Direct." : "";
        if (System.Windows.MessageBox.Show(this, $"Delete {proxy.Name}?{detail}", "VProxies", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        _proxies.Remove(proxy);
        for (var i = 0; i < _rules.Count; i++) if (_rules[i].Target == proxy.Id) _rules[i] = CreateRule(_rules[i].Id, _rules[i].ApplicationPath, "direct");
        var defaultTarget = (DefaultTargetBox.SelectedValue as string) == proxy.Id ? "direct" : DefaultTargetBox.SelectedValue as string;
        RefreshProxyGrid();
        RefreshTargets(defaultTarget, "direct");
        SaveSettings();
        NewProxyForm();
        AppendLog($"Deleted {proxy.Name}; affected routes now use Direct.");
    }

    private void NewRule_Click(object sender, RoutedEventArgs e) => NewRuleForm();

    private void NewRuleForm()
    {
        _editingRuleId = null;
        RuleGrid.SelectedItem = null;
        ApplicationPathBox.Clear();
        RuleTargetBox.SelectedValue = "direct";
        SaveRuleButton.Content = "ADD RULE";
        DeleteRuleButton.IsEnabled = false;
    }

    private void RuleGrid_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (RuleGrid.SelectedItem is not ApplicationRule rule) return;
        _editingRuleId = rule.Id;
        ApplicationPathBox.Text = rule.ApplicationPath;
        RuleTargetBox.SelectedValue = rule.Target;
        SaveRuleButton.Content = "UPDATE RULE";
        DeleteRuleButton.IsEnabled = true;
    }

    private void BrowseApplication_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Title = "Choose a Windows application", Filter = "Windows applications (*.exe)|*.exe", CheckFileExists = true, Multiselect = false };
        if (dialog.ShowDialog(this) == true) ApplicationPathBox.Text = dialog.FileName;
    }

    private void SaveRule_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var path = ApplicationPathBox.Text.Trim().Trim('"');
            if (string.IsNullOrWhiteSpace(path) || !path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) throw new ArgumentException("Choose a Windows .exe application.");
            path = Path.GetFullPath(path);
            if (!File.Exists(path)) throw new FileNotFoundException("The selected application does not exist.", path);
            var target = RuleTargetBox.SelectedValue as string ?? throw new ArgumentException("Choose how this application should connect.");
            if (!TargetExists(target)) throw new ArgumentException("The selected proxy no longer exists.");
            var id = _editingRuleId ?? Guid.NewGuid().ToString("N");
            if (_rules.Any(x => x.Id != id && x.ApplicationPath.Equals(path, StringComparison.OrdinalIgnoreCase))) throw new ArgumentException("This application already has a routing rule. Select it to edit the existing rule.");
            var rule = CreateRule(id, path, target);
            var index = _rules.FindIndex(x => x.Id == id);
            if (index >= 0) _rules[index] = rule; else _rules.Add(rule);
            RefreshRuleGrid();
            SaveSettings();
            RuleGrid.SelectedItem = rule;
            AppendLog($"Saved rule: {rule.ApplicationName} → {rule.TargetText}.");
        }
        catch (Exception ex) { ShowError(ex); }
    }

    private void DeleteRule_Click(object sender, RoutedEventArgs e)
    {
        if (_editingRuleId is null || _rules.FirstOrDefault(x => x.Id == _editingRuleId) is not { } rule) return;
        if (System.Windows.MessageBox.Show(this, $"Delete the routing rule for {rule.ApplicationName}?", "VProxies", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        _rules.Remove(rule);
        RefreshRuleGrid();
        SaveSettings();
        NewRuleForm();
        AppendLog($"Deleted rule for {rule.ApplicationName}.");
    }

    private void Settings_Changed(object sender, RoutedEventArgs e)
    {
        if (!_loadingSettings) SaveSettings();
    }

    private async void ApplyRouting_Click(object sender, RoutedEventArgs e)
    {
        ApplyRoutingButton.IsEnabled = false;
        try
        {
            SaveSettings();
            var routing = new RoutingSettings
            {
                Proxies = _proxies.ToArray(), Rules = _rules.ToArray(),
                DefaultTarget = DefaultTargetBox.SelectedValue as string ?? "direct",
                StrictRoute = StrictRouteBox.IsChecked == true, RemoteDns = RemoteDnsBox.IsChecked == true
            };
            _sensitiveLogValues.Clear();
            foreach (var proxy in _proxies)
                foreach (var secret in new[] { proxy.Username, proxy.Password })
                    if (!string.IsNullOrWhiteSpace(secret) && secret.Length >= 3) _sensitiveLogValues.Add(secret);
            if (_core.IsRunning) await _core.StopAsync();
            await _core.StartAsync(SingBoxConfigBuilder.Build(routing));
            SetRoutingState(true);
            var proxyRoutes = _rules.Count(x => x.Target is not "direct" and not "block");
            AppendLog($"Routing active: {_rules.Count} app rule(s), {proxyRoutes} proxy route(s), {_proxies.Count} proxy server(s) available simultaneously.");
        }
        catch (Exception ex)
        {
            SetRoutingState(false);
            ShowError(ex);
        }
        finally { ApplyRoutingButton.IsEnabled = true; }
    }

    private async void StopRouting_Click(object sender, RoutedEventArgs e)
    {
        StopRoutingButton.IsEnabled = false;
        try { await _core.StopAsync(); SetRoutingState(false); _sensitiveLogValues.Clear(); }
        catch (Exception ex) { ShowError(ex); }
    }

    private void SetRoutingState(bool active)
    {
        StatusText.Text = active ? "Routing active" : "Routing stopped";
        StatusText.Foreground = Brush(active ? "#62E6A6" : "#FF9BA8");
        StatusBadge.Background = Brush(active ? "#163D38" : "#2B3449");
        StatusDot.Fill = Brush(active ? "#3BE3A0" : "#FF718B");
        ConnectionDetailText.Text = active ? $"{_rules.Count} application rule(s) active" : "Apps use this PC connection";
        StopRoutingButton.IsEnabled = active;
    }

    private static readonly Regex AnsiPattern = new(@"\x1B\[[0-?]*[ -/]*[@-~]", RegexOptions.Compiled);

    private void AppendLog(string message)
    {
        if (!Dispatcher.CheckAccess()) { Dispatcher.BeginInvoke(() => AppendLog(message)); return; }
        message = AnsiPattern.Replace(SanitizeMessage(message), "").Trim();
        var level = message.Contains("ERROR", StringComparison.OrdinalIgnoreCase) || message.Contains("FATAL", StringComparison.OrdinalIgnoreCase) ? "ERROR" : message.Contains("WARN", StringComparison.OrdinalIgnoreCase) ? "WARN" : "INFO";
        var paragraph = new Paragraph { Margin = new Thickness(0, 0, 0, 3) };
        paragraph.Inlines.Add(new Run(DateTime.Now.ToString("HH:mm:ss")) { Foreground = Brush("#7186A3") });
        paragraph.Inlines.Add(new Run($"  {level,-5}  ") { Foreground = Brush(level == "ERROR" ? "#FF6689" : level == "WARN" ? "#FFC857" : "#45DFA2"), FontWeight = FontWeights.Bold });
        paragraph.Inlines.Add(new Run(message) { Foreground = Brush("#D8E5F5") });
        LogBox.Document.Blocks.Add(paragraph);
        while (LogBox.Document.Blocks.Count > 400 && LogBox.Document.Blocks.FirstBlock is Block first) LogBox.Document.Blocks.Remove(first);
        LogBox.ScrollToEnd();
    }

    private void ClearLog_Click(object sender, RoutedEventArgs e) => LogBox.Document.Blocks.Clear();
    private static SolidColorBrush Brush(string color) => new((MediaColor)MediaColorConverter.ConvertFromString(color));

    private void MainWindow_StateChanged(object? sender, EventArgs e)
    {
        if (WindowState != WindowState.Minimized) return;
        Hide();
        _trayIcon.ShowMinimizedNotice();
    }

    private void RestoreFromTray()
    {
        Dispatcher.BeginInvoke(() => { Show(); WindowState = WindowState.Normal; Activate(); });
    }

    private async void MainWindow_Closing(object? sender, CancelEventArgs e)
    {
        if (_exitRequested) return;
        e.Cancel = true;
        if (CloseToTrayBox.IsChecked == true)
        {
            Hide();
            _trayIcon.ShowMinimizedNotice();
            return;
        }
        await ExitApplicationAsync(confirm: true);
    }

    private async Task ExitApplicationAsync(bool confirm)
    {
        if (_shutdownInProgress) return;
        if (confirm && System.Windows.MessageBox.Show("Exit VProxies completely?\n\nActive routing will stop and all applications will return to their normal connection.", "Exit VProxies", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        _shutdownInProgress = true;
        try { SaveSettings(); } catch { }
        try { await _core.StopAsync(); }
        catch (Exception ex) { AppendLog("Shutdown cleanup warning: " + ex.Message); }
        _sensitiveLogValues.Clear();
        _core.Dispose();
        _trayIcon.Dispose();
        _exitRequested = true;
        Close();
        System.Windows.Application.Current.Shutdown();
    }

    private void ShowError(Exception exception)
    {
        var message = SanitizeMessage(exception.Message);
        AppendLog("ERROR: " + message);
        System.Windows.MessageBox.Show(this, message, "VProxies", MessageBoxButton.OK, MessageBoxImage.Error);
    }

    private string SanitizeMessage(string message)
    {
        foreach (var value in _sensitiveLogValues) message = message.Replace(value, "[hidden]", StringComparison.OrdinalIgnoreCase);
        return message;
    }
}
