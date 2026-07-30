using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows.Threading;
using Forms = System.Windows.Forms;

namespace SaegeulAiCompanionTray;

internal sealed class TrayController : IDisposable
{
    private static readonly string[] CredentialOverrides =
    [
        "OPENAI_API_KEY",
        "CODEX_API_KEY",
        "CODEX_ACCESS_TOKEN",
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_AUTH_TOKEN",
        "CLAUDE_CODE_OAUTH_TOKEN"
    ];

    private readonly CompanionOptions _options;
    private readonly Dispatcher _dispatcher;
    private readonly Action _shutdown;
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(2) };
    private readonly DispatcherTimer _pollTimer;
    private readonly SemaphoreSlim _processGate = new(1, 1);
    private readonly Forms.NotifyIcon _notifyIcon;
    private readonly Forms.ToolStripMenuItem _statusItem;
    private readonly Forms.ToolStripMenuItem _backendsItem;
    private readonly Forms.ToolStripMenuItem _startItem;
    private readonly Forms.ToolStripMenuItem _stopItem;
    private readonly Forms.ToolStripMenuItem _restartItem;

    private Process? _companion;
    private bool _desiredRunning = true;
    private bool _disposed;
    private GatewayState _state = GatewayState.Starting;
    private string _lastDetail = "시작 준비 중";

    public TrayController(CompanionOptions options, Dispatcher dispatcher, Action shutdown)
    {
        _options = options;
        _dispatcher = dispatcher;
        _shutdown = shutdown;

        _statusItem = new Forms.ToolStripMenuItem("상태: 시작 중") { Enabled = false };
        _backendsItem = new Forms.ToolStripMenuItem("Codex·Claude 확인 중") { Enabled = false };
        _startItem = new Forms.ToolStripMenuItem("게이트웨이 시작");
        _stopItem = new Forms.ToolStripMenuItem("게이트웨이 중지");
        _restartItem = new Forms.ToolStripMenuItem("게이트웨이 재시작");
        _startItem.Click += async (_, _) => await SetDesiredRunningAsync(true);
        _stopItem.Click += async (_, _) => await SetDesiredRunningAsync(false);
        _restartItem.Click += async (_, _) => await RestartAsync();

        var openStatusItem = new Forms.ToolStripMenuItem("로컬 상태 열기");
        openStatusItem.Click += (_, _) => OpenLocalStatus();
        var exitItem = new Forms.ToolStripMenuItem("트레이 종료 (게이트웨이 중지)");
        exitItem.Click += (_, _) => _dispatcher.Invoke(_shutdown);

        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add(new Forms.ToolStripMenuItem("Saegeul AI") { Enabled = false });
        menu.Items.Add(_statusItem);
        menu.Items.Add(_backendsItem);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add(_startItem);
        menu.Items.Add(_stopItem);
        menu.Items.Add(_restartItem);
        menu.Items.Add(openStatusItem);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add(exitItem);

        _notifyIcon = new Forms.NotifyIcon
        {
            ContextMenuStrip = menu,
            Text = "Saegeul AI · 시작 중",
            Visible = true,
            Icon = CreateStatusIcon(Color.Goldenrod)
        };
        _notifyIcon.DoubleClick += (_, _) => ShowStatusBalloon();

        _pollTimer = new DispatcherTimer(TimeSpan.FromSeconds(5), DispatcherPriority.Background,
            async (_, _) => await PollAsync(), dispatcher);
    }

    public void Start()
    {
        _pollTimer.Start();
        _ = EnsureCompanionAsync();
    }

    private async Task SetDesiredRunningAsync(bool running)
    {
        _desiredRunning = running;
        if (running)
        {
            SetState(GatewayState.Starting, "게이트웨이를 시작하는 중");
            await EnsureCompanionAsync();
        }
        else
        {
            await StopOwnedCompanionAsync();
            SetState(GatewayState.Stopped, "사용자가 중지함");
        }
    }

    private async Task RestartAsync()
    {
        _desiredRunning = true;
        await StopOwnedCompanionAsync();
        SetState(GatewayState.Starting, "게이트웨이를 다시 시작하는 중");
        await EnsureCompanionAsync();
    }

    private async Task EnsureCompanionAsync()
    {
        if (!_desiredRunning || _disposed) return;
        await _processGate.WaitAsync();
        try
        {
            if (!_desiredRunning || _disposed || await ReadHealthAsync() is not null) return;
            if (_companion is { HasExited: false }) return;

            var startInfo = new ProcessStartInfo
            {
                FileName = "python.exe",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                WorkingDirectory = Path.GetDirectoryName(_options.CompanionPath)!
            };
            startInfo.ArgumentList.Add(_options.CompanionPath);
            startInfo.ArgumentList.Add("--name");
            startInfo.ArgumentList.Add(Environment.MachineName);
            startInfo.ArgumentList.Add("--gateway-port");
            startInfo.ArgumentList.Add(_options.GatewayPort.ToString());
            startInfo.ArgumentList.Add("--tailscale-https-port");
            startInfo.ArgumentList.Add(_options.TailscaleHttpsPort.ToString());
            foreach (var key in CredentialOverrides) startInfo.Environment.Remove(key);
            startInfo.Environment["NO_COLOR"] = "1";
            startInfo.Environment["PYTHONUTF8"] = "1";

            var process = new Process { StartInfo = startInfo, EnableRaisingEvents = true };
            process.OutputDataReceived += (_, eventArgs) => CaptureDetail(eventArgs.Data);
            process.ErrorDataReceived += (_, eventArgs) => CaptureDetail(eventArgs.Data);
            process.Exited += (_, _) => _dispatcher.BeginInvoke(async () =>
            {
                if (_disposed || !_desiredRunning) return;
                SetState(GatewayState.Error, "게이트웨이가 종료됨 · 자동 재시작 대기");
                await Task.Delay(TimeSpan.FromSeconds(3));
                await EnsureCompanionAsync();
            });
            if (!process.Start()) throw new InvalidOperationException("Python companion을 시작하지 못했어.");
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            _companion?.Dispose();
            _companion = process;
        }
        catch (Exception exception)
        {
            SetState(GatewayState.Error, ShortMessage(exception.Message));
        }
        finally
        {
            _processGate.Release();
        }
    }

    private async Task StopOwnedCompanionAsync()
    {
        await _processGate.WaitAsync();
        try
        {
            var process = _companion;
            _companion = null;
            if (process is null) return;
            try
            {
                if (!process.HasExited)
                {
                    process.Kill(entireProcessTree: true);
                    await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5));
                }
            }
            catch (InvalidOperationException) { }
            finally
            {
                process.Dispose();
            }
        }
        finally
        {
            _processGate.Release();
        }
    }

    private async Task PollAsync()
    {
        if (_disposed) return;
        var health = await ReadHealthAsync();
        if (health is not null)
        {
            SetState(GatewayState.Running, "휴대폰 연결 준비됨", health.Value.Backends);
            return;
        }
        if (!_desiredRunning)
        {
            SetState(GatewayState.Stopped, "사용자가 중지함");
            return;
        }
        SetState(GatewayState.Starting, _lastDetail);
        await EnsureCompanionAsync();
    }

    private async Task<(string Backends, string Provider)?> ReadHealthAsync()
    {
        try
        {
            using var response = await _http.GetAsync($"http://127.0.0.1:{_options.GatewayPort}/health");
            if (!response.IsSuccessStatusCode) return null;
            await using var stream = await response.Content.ReadAsStreamAsync();
            using var document = await JsonDocument.ParseAsync(stream);
            var root = document.RootElement;
            if (root.GetProperty("status").GetString() != "ok" ||
                root.GetProperty("provider").GetString() != "computer-cli") return null;
            var backends = string.Join(" + ", root.GetProperty("backends")
                .EnumerateArray().Select(value => value.GetString()).Where(value => value is not null));
            return (backends, "computer-cli");
        }
        catch (Exception exception) when (exception is HttpRequestException or TaskCanceledException
                                           or JsonException or InvalidOperationException)
        {
            return null;
        }
    }

    private void SetState(GatewayState state, string detail, string? backends = null)
    {
        if (_disposed) return;
        var changed = state != _state;
        _state = state;
        _lastDetail = ShortMessage(detail);
        var stateText = state switch
        {
            GatewayState.Running => "연결됨",
            GatewayState.Starting => "시작 중",
            GatewayState.Stopped => "중지됨",
            _ => "오류"
        };
        _statusItem.Text = $"상태: {stateText} · {_lastDetail}";
        _backendsItem.Text = string.IsNullOrWhiteSpace(backends)
            ? "Codex·Claude 상태 확인 중"
            : $"백엔드: {backends}";
        _startItem.Enabled = state is GatewayState.Stopped or GatewayState.Error;
        _stopItem.Enabled = state is not GatewayState.Stopped;
        _restartItem.Enabled = state is not GatewayState.Starting;
        _notifyIcon.Text = ShortTooltip($"Saegeul AI · {stateText}");
        ReplaceIcon(state switch
        {
            GatewayState.Running => Color.MediumSeaGreen,
            GatewayState.Starting => Color.Goldenrod,
            GatewayState.Stopped => Color.Gray,
            _ => Color.IndianRed
        });
        if (changed && state is GatewayState.Running or GatewayState.Error)
            ShowStatusBalloon();
    }

    private void CaptureDetail(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return;
        _lastDetail = ShortMessage(value);
    }

    private void ShowStatusBalloon()
    {
        var title = _state == GatewayState.Running ? "AI 게이트웨이 연결됨" : "AI 게이트웨이 상태";
        _notifyIcon.ShowBalloonTip(2500, title, _lastDetail,
            _state == GatewayState.Error ? Forms.ToolTipIcon.Error : Forms.ToolTipIcon.Info);
    }

    private void OpenLocalStatus()
    {
        try
        {
            Process.Start(new ProcessStartInfo(
                $"http://127.0.0.1:{_options.GatewayPort}/health") { UseShellExecute = true });
        }
        catch (Exception exception)
        {
            SetState(GatewayState.Error, exception.Message);
        }
    }

    private void ReplaceIcon(Color color)
    {
        var previous = _notifyIcon.Icon;
        _notifyIcon.Icon = CreateStatusIcon(color);
        previous?.Dispose();
    }

    private static Icon CreateStatusIcon(Color color)
    {
        using var bitmap = new Bitmap(32, 32);
        using (var graphics = Graphics.FromImage(bitmap))
        {
            graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            graphics.Clear(Color.Transparent);
            using var brush = new SolidBrush(color);
            graphics.FillEllipse(brush, 2, 2, 28, 28);
            using var font = new Font("Segoe UI", 10, FontStyle.Bold, GraphicsUnit.Pixel);
            var label = "AI";
            var size = graphics.MeasureString(label, font);
            graphics.DrawString(label, font, Brushes.White, (32 - size.Width) / 2, (32 - size.Height) / 2);
        }
        var handle = bitmap.GetHicon();
        try
        {
            using var borrowed = Icon.FromHandle(handle);
            return (Icon)borrowed.Clone();
        }
        finally
        {
            DestroyIcon(handle);
        }
    }

    private static string ShortMessage(string value)
    {
        var text = string.Join(
            " ",
            value.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries)
        ).Trim();
        return text.Length > 96 ? text[..93] + "..." : text;
    }

    private static string ShortTooltip(string value) => value.Length <= 63 ? value : value[..63];

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _desiredRunning = false;
        _pollTimer.Stop();
        StopOwnedCompanionAsync().GetAwaiter().GetResult();
        _notifyIcon.Visible = false;
        _notifyIcon.Icon?.Dispose();
        _notifyIcon.Dispose();
        _http.Dispose();
        _processGate.Dispose();
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr handle);

    private enum GatewayState
    {
        Starting,
        Running,
        Stopped,
        Error
    }
}
