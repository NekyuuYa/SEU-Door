#pragma once

#include <stdint.h>
#include <stdbool.h>

typedef struct {
    int32_t  device_id;
    int32_t  project_id;
    uint8_t  credential[32];
    char     ble_mac[18];
    uint32_t credential_id;
    bool     valid;
} stored_credential_t;

// Initialize NVS
void cred_nvs_init(void);

// Load credential from NVS (returns false if not found)
bool cred_load(stored_credential_t *out);

// Save credential to NVS
bool cred_save(const stored_credential_t *cred);

// Erase credential from NVS
void cred_erase(void);
