namespace CoulombMppt.Ble;

public enum ConnectionState
{
    Idle,
    Scanning,
    Connecting,
    DiscoveringServices,
    Ready,
    Reconnecting,
    Failed,
}
