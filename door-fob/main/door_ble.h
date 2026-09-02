#pragma once

#include "door_crypto.h"
#include <stdbool.h>

typedef enum {
    DOOR_OPEN_OK = 0,
    DOOR_OPEN_ERR_SCAN_TIMEOUT,
    DOOR_OPEN_ERR_CONNECT,
    DOOR_OPEN_ERR_DISCOVERY,
    DOOR_OPEN_ERR_HEADER,
    DOOR_OPEN_ERR_PACKET,
    DOOR_OPEN_ERR_OPEN,
    DOOR_OPEN_ERR_CRED_REFRESH,
} door_open_result_t;

typedef struct {
    int32_t  device_id;
    int32_t  project_id;
    uint8_t  credential[32];
    char     ble_mac[18];  // "XX:XX:XX:XX:XX:XX"
    uint32_t credential_id; // needed for rolling-credential refresh (0x76)
} door_config_t;

// Initialize BLE stack
void door_ble_init(void);

// Open door via BLE protocol.
// On a stale rolling credential (lock returns code 27) the credential is
// refreshed in place: config->credential is updated and, if out_refreshed is
// non-NULL, *out_refreshed is set true so the caller can persist the new value.
door_open_result_t door_ble_open(door_config_t *config, bool *out_refreshed);
