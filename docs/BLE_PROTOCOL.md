# BLE Protocol (Implementation Notes)

This file is the **in-app reference** for the protocol the controller speaks.
The full reverse-engineering report lives at
[`../../decompiled/BLE_PROTOCOL_EXTRACTED.md`](../../decompiled/BLE_PROTOCOL_EXTRACTED.md);
this one is shorter and tells you which Kotlin file does each thing.

## Transport

Service: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` (Nordic UART Service)

Two characteristics under the service. The original vendor app wrote to the
notify char (`…0003`), which is non-standard. We probe both and pick by
property — see `data/ble/NusTransport.kt`.

```
…0002   typical NUS write     (we try here first)
…0003   typical NUS notify    (vendor app wrote here too; fallback for writes)
```

After connect we negotiate MTU 64 (`data/ble/BleConstants.kt`) and enable
notifications via the standard CCCD descriptor.

## Frame format

Modbus RTU, slave address 0x01. The full PDU is just:

```
+--------+--------+----- payload -----+----------+----------+
| 0x01   |  fn    |  …                | CRC LO   | CRC HI   |
+--------+--------+-------------------+----------+----------+
```

CRC-16/Modbus (poly 0xA001, init 0xFFFF, low byte first) — implemented in
`data/modbus/ModbusCrc.kt`.

Function codes used by the firmware:

| fn   | Meaning                            | Kotlin builder                                |
| ---- | ---------------------------------- | --------------------------------------------- |
| 0x03 | Read Holding Registers             | `ModbusFrame.read(address, quantity)`         |
| 0x10 | Write Multiple Registers (qty = 1) | `ModbusFrame.writeSingle(address, value)`     |

## Live telemetry (poll)

Build with `MpptProtocol.pollLive()` → `01 03 00 01 00 10 <crc>`.

Response is fn=0x03 / byteCount=20 → 10 registers, decoded by
`BleMpptSource.emitLive(...)`:

| Reg | Field              | Scale | Notes                       |
| --- | ------------------ | ----- | --------------------------- |
|  0  | (model_type)       | —     | Discarded.                  |
|  1  | `batteryVoltage`   | /10   | V (likely battery bus).     |
|  2  | `chargeCurrent`    | /10   | A — PV → battery.           |
|  3  | `dischargeCurrent` | /10   | A — battery → load.         |
|  4  | `temperatureC`     | /100  | Controller temp.            |
|  5  | `solarStatusRaw`   | enum  | Codes not yet confirmed.    |
|  6  | `workStatusRaw`    | enum  | "                           |
|  7  | `powerStatusRaw`   | enum  | "                           |
|  8  | totalEnergy LO     | combined | `(1000*hi + lo)/10` kWh. |
|  9  | totalEnergy HI     | "       | "                         |

Polling cadence ≈ 4 Hz (`BleMpptSource.startPolling()` — 250 ms loop).

**PV-side fields are not exposed** in the polled register range. The app
displays `approxPvWatts = batteryVoltage × chargeCurrent` as a placeholder.
Worth scanning `0x0000..0x00FF` on hardware to find the real PV regs.

## Settings (read + write)

Read with `MpptProtocol.pollSettings()` → `01 03 10 01 00 0F <crc>`.

Response is fn=0x03 / byteCount=18 → 9 registers, decoded by
`BleMpptSource.readSettings()`:

| Reg | Address  | Field                    | Scale   | UI control                |
| --- | -------- | ------------------------ | ------- | ------------------------- |
|  0  | 0x1001   | `batteryType`            | enum    | Picker (codes TBD)        |
|  1  | 0x1002   | `timerHours`             | int     | Picker                    |
|  2  | 0x1003   | `timerMinutes`           | int     | Picker                    |
|  3  | 0x1004   | `chargeVoltageSetpoint`  | /10     | V input                   |
|  4  | 0x1005   | `outputMode`             | enum    | Picker (codes TBD)        |
|  5  | 0x1006   | `cutoffVoltageSetpoint`  | /10     | V input                   |
|  6  | 0x1007   | `manualLoadOn`           | 0/1     | Switch                    |
|  7  | 0x1008   | `voltageMonitorMode`     | enum    | Picker (codes TBD)        |
|  8  | 0x1009   | `recoveryVoltageSetpoint`| /10     | V input                   |

Writes go via `MpptProtocol.writeRegister(address, value)`, which is just
`fn=0x10`, qty=1, byteCount=2, value=BE16. The firmware ACKs with a fn=0x10
echo (`slave fn addr qty crc`) — that's what `BleMpptSource.writeSetting()`
waits on.

## What's missing / TODO

1. **Enum value lists.** Battery type, output mode, voltage monitor mode —
   the vendor APK pulls these from `api/Product/getProductInfo?ukey=1`
   (now dead). On real hardware, try each plausible 16-bit value and watch
   the behaviour, or sniff a working firmware.
2. **PV-side telemetry.** Probably lives somewhere in `0x0000..0x00FF`.
   A "Modbus scan" debug screen would find it.
3. **Foreground service.** Once you want background polling, add a
   `ConnectedDevice` foreground service that owns the `MpptRepository`
   lifecycle. Permission is already in the manifest.
4. **History recording.** Add a Room DAO + Worker that snapshots
   `MpptRepository.latest` every minute, then a chart screen.
