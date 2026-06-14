using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace CoulombMppt.Api;

// Self-signed RSA cert used by Kestrel and pinned by the remote client (cert
// SHA-256 thumbprint travels in the QR). Persisted as a password-protected PFX
// in %APPDATA% so the same cert is reused across launches — otherwise pinned
// clients would all break on every restart.
public static class ServerCert
{
    private static readonly string Path = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "CoulombMppt", "server.pfx");

    private const string Password = "coulomb-local";

    public static X509Certificate2 LoadOrCreate()
    {
        if (File.Exists(Path))
        {
            try { return X509CertificateLoader.LoadPkcs12FromFile(Path, Password,
                       X509KeyStorageFlags.MachineKeySet | X509KeyStorageFlags.Exportable); }
            catch { /* fall through and regenerate */ }
        }
        var cert = Create();
        Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
        File.WriteAllBytes(Path, cert.Export(X509ContentType.Pfx, Password));
        return cert;
    }

    public static string Sha256Thumbprint(X509Certificate2 cert)
    {
        var hash = SHA256.HashData(cert.RawData);
        return Convert.ToHexString(hash).ToUpperInvariant();
    }

    private static X509Certificate2 Create()
    {
        using var rsa = RSA.Create(2048);
        var req = new CertificateRequest(
            "CN=CoulombMPPT Local API",
            rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);

        // Mark as TLS server. SANs include 'localhost' and every local IPv4 so
        // the cert validates against either form. Pinning means the client
        // doesn't actually check SAN, but populated SANs make some HTTP clients
        // happier.
        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName("localhost");
        try
        {
            foreach (var ip in System.Net.Dns.GetHostAddresses(System.Net.Dns.GetHostName()))
                if (ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
                    san.AddIpAddress(ip);
        }
        catch { /* offline machine — name-only SAN is fine */ }
        req.CertificateExtensions.Add(san.Build());
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        req.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
        req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, false));   // serverAuth

        return req.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddDays(-1),
            DateTimeOffset.UtcNow.AddYears(10));
    }
}
