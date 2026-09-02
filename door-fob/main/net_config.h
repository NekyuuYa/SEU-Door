#pragma once

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

// Network / API configuration stored in its own NVS namespace, separate from
// the door credential. All string fields are optional (absent = not set).
// Provisioned field-by-field over the BLE config service (0xFF12).

#define NETCFG_WIFI_SSID_MAX    33
#define NETCFG_WIFI_PASS_MAX    65
#define NETCFG_PORTAL_USER_MAX  32
#define NETCFG_PORTAL_PASS_MAX  64
#define NETCFG_SERVER_URL_MAX   128
#define NETCFG_SESSION_MAX      64
#define NETCFG_USER_ID_MAX      48
#define NETCFG_IDENTITY_MAX     80

// NVS key names (also used by the BLE config service to route JSON fields)
#define NETCFG_KEY_WIFI_SSID     "wifi_ssid"
#define NETCFG_KEY_WIFI_PASS     "wifi_pass"
#define NETCFG_KEY_PORTAL_USER   "portal_user"
#define NETCFG_KEY_PORTAL_PASS   "portal_pass"
#define NETCFG_KEY_SERVER_URL    "server_url"
#define NETCFG_KEY_SESSION_SEC   "session_secret"
#define NETCFG_KEY_USER_ID       "user_id"
#define NETCFG_KEY_IDENTITY      "identity_code"

typedef struct {
    char wifi_ssid[NETCFG_WIFI_SSID_MAX];
    char wifi_pass[NETCFG_WIFI_PASS_MAX];
    char portal_user[NETCFG_PORTAL_USER_MAX];
    char portal_pass[NETCFG_PORTAL_PASS_MAX];
    char server_url[NETCFG_SERVER_URL_MAX];
    char session_secret[NETCFG_SESSION_MAX];
    char user_id[NETCFG_USER_ID_MAX];
    char identity_code[NETCFG_IDENTITY_MAX];
    bool present;   // true if at least one field is stored
} net_config_t;

// Initialize the net-cfg NVS namespace (must be called after nvs_flash_init)
void netcfg_nvs_init(void);

// Load all fields; out->present is false when nothing has ever been stored
bool netcfg_load(net_config_t *out);

// Save the full config
bool netcfg_save(const net_config_t *cfg);

// Update a single field by NVS key name (read-modify-write).
// Returns false if the key is unknown or the value does not fit.
bool netcfg_set_field(const char *key, const char *value);

// Last known-good unix timestamp (fallback clock when SNTP is unavailable)
int64_t netcfg_get_last_ts(void);
void    netcfg_set_last_ts(int64_t ts);
