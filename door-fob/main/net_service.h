#pragma once

#include <stdint.h>

typedef enum {
    NET_OK = 0,
    NET_ERR_NO_CONFIG,   // no wifi_ssid provisioned
    NET_ERR_WIFI,        // association or DHCP failed
    NET_ERR_TIME,        // no usable clock (SNTP and fallback both failed)
} net_err_t;

// Bring the network up: WiFi STA connect -> campus portal auth (best effort,
// skipped when portal credentials are not provisioned) -> SNTP time sync.
// Blocks up to ~60s. Must run in a task with enough stack for TLS (~16KB+).
net_err_t net_bring_up(void);

// Stop WiFi (and time has been latched into NVS as a fallback clock)
void net_tear_down(void);

// Best-effort current unix time (seconds). Uses system time when it looks
// sane, otherwise falls back to the last timestamp latched in NVS.
// Returns 0 when no usable time source exists.
int64_t net_now(void);
