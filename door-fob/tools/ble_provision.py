#!/usr/bin/env python3
"""Provision the fob's BLE config service (0xFF10) from a JSON file.

Simulates the App's export write: connects to the fob while it advertises the
0xFF12 config service (long-press >=3s on the button opens the 60s window, or
the bench selftest build opens one at boot) and writes the JSON fields the
config service understands.

Fields (all optional, field-wise update):
  door:    device_id, project_id, credential (64 hex), ble_mac, credential_id
  network: wifi_ssid, wifi_pass, portal_user, portal_pass
  session: server_url, session_secret, user_id, identity_code

Usage:
  python3 ble_provision.py session.json [scan_timeout_s]

Example session.json (real values come from the App's OAuth export):
  {
    "server_url": "https://zhuli104.whxinna.com",
    "session_secret": "<from app login>",
    "user_id": "<uuid>",
    "identity_code": "<from app login>",
    "wifi_ssid": "SEU-WLAN",
    "wifi_pass": "",
    "portal_user": "<portal account>",
    "portal_pass": "<portal password pre-%-encoded>"
  }
"""
import asyncio, json, sys
from bleak import BleakScanner, BleakClient

SVC = "0000ff10-0000-1000-8000-00805f9b34fb"
CHR_WRITE = "0000ff12-0000-1000-8000-00805f9b34fb"
CHR_STATUS = "0000ff13-0000-1000-8000-00805f9b34fb"

def on_notify(_c, data):
    print(f"STATUS NOTIFY: {int.from_bytes(data, 'big')} (0=ok)", flush=True)

async def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "session.json"
    scan_to = float(sys.argv[2]) if len(sys.argv) > 2 else 45.0
    with open(path, "r", encoding="utf-8") as f:
        payload = json.dumps(json.load(f), ensure_ascii=False)

    print(f"scanning up to {scan_to:.0f}s for the fob (config service 0xFF10)...", flush=True)
    dev = await BleakScanner.find_device_by_filter(
        lambda d, ad: any(u.lower() == SVC for u in (ad.service_uuids or [])),
        timeout=scan_to)
    if not dev:
        print("NOT FOUND: fob is not in its config window "
              "(long-press >=3s to open it)", flush=True)
        return 1
    print(f"found: {dev.name} {dev.address}", flush=True)

    async with BleakClient(dev, timeout=15) as client:
        await client.start_notify(CHR_STATUS, on_notify)
        try:
            info = await client.read_gatt_char("0000ff11-0000-1000-8000-00805f9b34fb")
            print(f"INFO: {info.hex()}", flush=True)
        except Exception as e:
            print(f"info read failed: {e}", flush=True)
        data = payload.encode()
        print(f"writing {len(data)} bytes to 0xFF12: {payload}", flush=True)
        await client.write_gatt_char(CHR_WRITE, data, response=True)
        await asyncio.sleep(1.5)
        try:
            st = await client.read_gatt_char(CHR_STATUS)
            print(f"STATUS READ: {int.from_bytes(st, 'big')} (0=ok)", flush=True)
        except Exception as e:
            print(f"status read failed: {e}", flush=True)
    return 0

if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
