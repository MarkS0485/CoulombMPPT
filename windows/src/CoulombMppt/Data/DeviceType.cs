namespace CoulombMppt.Data;

// What kind of device a paired entry is, so the client can pick the right path.
// Serialised as part of MpptController; defaults to the original Modbus-over-
// Nordic-UART controller so controllers.json written by older builds keeps
// working with no migration.
public enum DeviceType
{
    // Original Chinese MPPT: Modbus-RTU over a Nordic UART GATT service,
    // connection-oriented polling. Handled by MpptClient's GATT path.
    GenericModbusNus = 0,

    // Victron SmartSolar/BlueSolar "Instant Readout": telemetry decoded
    // passively from the BLE manufacturer advertisement (AES-CTR with a
    // per-device key) — no connection. Handled by MpptClient's Victron watch.
    VictronInstantReadout = 1,
}
