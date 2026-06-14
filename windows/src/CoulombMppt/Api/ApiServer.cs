using System.Net;
using System.Net.Sockets;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.Logging;
using CoulombMppt.Services;

namespace CoulombMppt.Api;

// Embedded HTTPS API server. Self-contained: owns its lifecycle, the HMAC
// middleware wiring, and the route table. Reaches the MPPT client / stores via
// ServiceLocator so it doesn't need its own DI container.
public sealed class ApiServer
{
    public PairedClientStore PairedClients { get; }
    public int Port { get; private set; } = 8800;
    public string CertSha256 { get; private set; } = "";

    public bool Running => _host != null;
    public event Action? RunningChanged;

    private WebApplication? _host;
    private readonly object _lock = new();

    public ApiServer()
    {
        PairedClients = new PairedClientStore();
        // Compute the cert thumbprint EAGERLY. The pairing QR must carry it even
        // before the server is started — users click Generate before Start, and
        // an empty t= gives the client a "cert pin mismatch" that looks like a
        // TLS fault when it's really a UX-ordering one.
        try
        {
            var cert = ServerCert.LoadOrCreate();
            CertSha256 = ServerCert.Sha256Thumbprint(cert);
        }
        catch (Exception ex) { Log.W("api", $"cert preload failed: {ex.Message}"); }
    }

    public async Task StartAsync(int port)
    {
        lock (_lock)
        {
            if (_host != null) return;
            Port = port;
        }

        var cert = ServerCert.LoadOrCreate();
        CertSha256 = ServerCert.Sha256Thumbprint(cert);

        var builder = WebApplication.CreateBuilder();
        builder.WebHost.ConfigureKestrel(o =>
        {
            o.ListenAnyIP(port, lo =>
            {
                lo.UseHttps(cert);
                lo.Protocols = HttpProtocols.Http1AndHttp2;
            });
        });
        builder.Logging.ClearProviders();

        var app = builder.Build();
        app.Use(async (ctx, next) =>
        {
            // /api/ping is open so clients can probe pairing without auth.
            if (ctx.Request.Path.StartsWithSegments("/api/ping"))
            {
                await next();
                return;
            }
            if (!await HmacAuth.Validate(ctx, PairedClients)) return;
            await next();
        });

        ApiEndpoints.Map(app);

        _host = app;
        await app.StartAsync().ConfigureAwait(false);
        Log.I("api", $"server up on https://0.0.0.0:{port} thumbprint={CertSha256}");
        RunningChanged?.Invoke();
    }

    public async Task StopAsync()
    {
        WebApplication? h;
        lock (_lock) { h = _host; _host = null; }
        if (h == null) return;
        try { await h.StopAsync().ConfigureAwait(false); }
        catch (Exception ex) { Log.W("api", $"stop threw: {ex.Message}"); }
        await h.DisposeAsync().ConfigureAwait(false);
        Log.I("api", "server stopped");
        RunningChanged?.Invoke();
    }

    // --- Pairing URI -------------------------------------------------

    public string BuildPairingUri(PairedClient c, string host)
    {
        // The remote client parses this. Keys are short to keep the QR dense:
        // u=url, k=keyId, s=secret(base64-url), t=cert thumbprint.
        var secretUrl = c.SecretBase64
            .Replace('+', '-').Replace('/', '_').TrimEnd('=');
        return $"coulomb://pair?u=https%3A%2F%2F{host}%3A{Port}" +
               $"&k={c.KeyId}&s={secretUrl}&t={CertSha256}";
    }

    public static IEnumerable<string> EnumerateLanAddresses()
    {
        foreach (var ip in Dns.GetHostAddresses(Dns.GetHostName()))
            if (ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))
                yield return ip.ToString();
    }
}
