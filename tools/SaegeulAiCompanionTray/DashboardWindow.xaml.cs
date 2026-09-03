using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Media;
using MediaColor = System.Windows.Media.Color;
using MediaColorConverter = System.Windows.Media.ColorConverter;
using WpfMessageBox = System.Windows.MessageBox;

namespace SaegeulAiCompanionTray;

public partial class DashboardWindow : Window
{
    private readonly TrayController _controller;
    private readonly CompanionOptions _options;

    internal DashboardWindow(TrayController controller, CompanionOptions options)
    {
        _controller = controller;
        _options = options;
        InitializeComponent();

        LocalEndpointText.Text = $"http://127.0.0.1:{options.GatewayPort}";
        TailscaleEndpointText.Text = $"https://[Device]:{options.TailscaleHttpsPort}";
        UpdateStatus();
    }

    public void UpdateStatus(string statusText = "정상 작동 중", bool isRunning = true, string backends = "Codex · Claude · Ollama")
    {
        Dispatcher.Invoke(() =>
        {
            if (isRunning)
            {
                StatusBadge.Background = new SolidColorBrush((MediaColor)MediaColorConverter.ConvertFromString("#064E3B"));
                StatusBadgeText.Foreground = new SolidColorBrush((MediaColor)MediaColorConverter.ConvertFromString("#34D399"));
                StatusBadgeText.Text = $"● {statusText}";
            }
            else
            {
                StatusBadge.Background = new SolidColorBrush((MediaColor)MediaColorConverter.ConvertFromString("#451A03"));
                StatusBadgeText.Foreground = new SolidColorBrush((MediaColor)MediaColorConverter.ConvertFromString("#F87171"));
                StatusBadgeText.Text = $"■ {statusText}";
            }
            BackendSummaryText.Text = backends;
        });
    }

    private void OnAdbReverseClick(object sender, RoutedEventArgs e)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "adb",
                Arguments = $"reverse tcp:{_options.GatewayPort} tcp:{_options.GatewayPort}",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            using var proc = Process.Start(psi);
            proc?.WaitForExit(3000);
            var output = proc?.StandardOutput.ReadToEnd() ?? "";
            var error = proc?.StandardError.ReadToEnd() ?? "";

            if (proc?.ExitCode == 0)
            {
                WpfMessageBox.Show(
                    $"ADB 포트포워딩 성공!\n모바일 기기에서 http://127.0.0.1:{_options.GatewayPort} 로 즉시 접근할 수 있습니다.",
                    "Saegeul ADB Port Forwarding",
                    MessageBoxButton.OK,
                    MessageBoxImage.Information
                );
            }
            else
            {
                WpfMessageBox.Show(
                    $"ADB 실행 중 알림:\n{error.Trim() + "\n" + output.Trim()}\n\n기기가 USB 디버깅으로 연결되어 있는지 확인해주세요.",
                    "Saegeul ADB Port Forwarding",
                    MessageBoxButton.OK,
                    MessageBoxImage.Warning
                );
            }
        }
        catch (Exception ex)
        {
            WpfMessageBox.Show(
                $"ADB 실행 오류: {ex.Message}\nADB가 시스템 PATH에 등록되어 있는지 확인해주세요.",
                "Saegeul ADB Port Forwarding",
                MessageBoxButton.OK,
                MessageBoxImage.Error
            );
        }
    }

    private void OnOpenBrowserClick(object sender, RoutedEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = $"http://127.0.0.1:{_options.GatewayPort}",
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            WpfMessageBox.Show($"브라우저 열기 실패: {ex.Message}", "Saegeul AI", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private async void OnRestartGatewayClick(object sender, RoutedEventArgs e)
    {
        RestartGatewayButton.IsEnabled = false;
        try
        {
            await _controller.RestartAsync();
            WpfMessageBox.Show("게이트웨이가 성공적으로 재시작되었습니다.", "Saegeul AI", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        finally
        {
            RestartGatewayButton.IsEnabled = true;
        }
    }

    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        // Hide to system tray instead of closing
        e.Cancel = true;
        Hide();
    }
}
