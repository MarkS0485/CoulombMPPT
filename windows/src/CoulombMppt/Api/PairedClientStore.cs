using System.IO;
using System.Security.Cryptography;
using System.Text.Json;

namespace CoulombMppt.Api;

public sealed class PairedClientStore
{
    private static readonly JsonSerializerOptions s_jsonOpts = new() { WriteIndented = true };

    private readonly string _path;
    private readonly object _lock = new();
    private List<PairedClient> _all = new();

    public PairedClientStore()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "CoulombMppt");
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "paired-clients.json");
        Load();
    }

    public event Action? Changed;

    public IReadOnlyList<PairedClient> All
    {
        get { lock (_lock) return _all.ToArray(); }
    }

    public PairedClient? FindByKeyId(string keyId)
    {
        lock (_lock) return _all.FirstOrDefault(c =>
            c.KeyId.Equals(keyId, StringComparison.Ordinal) && !c.Revoked);
    }

    // Generate a fresh keyId + 32-byte random secret. The caller is responsible
    // for surfacing this through a one-time channel (the QR).
    public PairedClient CreatePending(string label)
    {
        var keyId  = Guid.NewGuid().ToString("N")[..12];
        var secret = new byte[32];
        RandomNumberGenerator.Fill(secret);
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var c = new PairedClient(
            KeyId:        keyId,
            SecretBase64: Convert.ToBase64String(secret),
            Label:        string.IsNullOrWhiteSpace(label) ? "Phone" : label.Trim(),
            PairedAtMs:   now,
            LastSeenAtMs: 0);
        lock (_lock) { _all.Add(c); Save(); }
        Changed?.Invoke();
        return c;
    }

    public void TouchLastSeen(string keyId)
    {
        lock (_lock)
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            for (int i = 0; i < _all.Count; i++)
                if (_all[i].KeyId == keyId)
                    _all[i] = _all[i] with { LastSeenAtMs = now };
            Save();
        }
        Changed?.Invoke();
    }

    public void Revoke(string keyId)
    {
        lock (_lock)
        {
            for (int i = 0; i < _all.Count; i++)
                if (_all[i].KeyId == keyId)
                    _all[i] = _all[i] with { Revoked = true };
            Save();
        }
        Changed?.Invoke();
    }

    public void Delete(string keyId)
    {
        lock (_lock) { _all.RemoveAll(c => c.KeyId == keyId); Save(); }
        Changed?.Invoke();
    }

    private void Load()
    {
        if (!File.Exists(_path)) return;
        try
        {
            var b = JsonSerializer.Deserialize<List<PairedClient>>(File.ReadAllText(_path));
            if (b != null) _all = b;
        }
        catch { }
    }

    private void Save() =>
        File.WriteAllText(_path,
            JsonSerializer.Serialize(_all, s_jsonOpts));
}
