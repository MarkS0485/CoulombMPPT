namespace CoulombMppt.Api;

// One paired remote app. The secret is a 32-byte random number, stored
// base64; we use HMAC-SHA256 over canonical request strings so the secret
// never travels on the wire after pairing.
public sealed record PairedClient(
    string KeyId,
    string SecretBase64,
    string Label,
    long   PairedAtMs,
    long   LastSeenAtMs,
    bool   Revoked = false);
