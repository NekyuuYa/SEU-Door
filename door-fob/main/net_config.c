#include "net_config.h"
#include "nvs.h"
#include "esp_log.h"
#include <string.h>

#define TAG "net-cfg"

#define NETCFG_NAMESPACE "net-cfg"

static nvs_handle_t s_handle;

static const struct {
    const char *key;
    size_t max;
} s_fields[] = {
    { NETCFG_KEY_WIFI_SSID,   NETCFG_WIFI_SSID_MAX - 1 },
    { NETCFG_KEY_WIFI_PASS,   NETCFG_WIFI_PASS_MAX - 1 },
    { NETCFG_KEY_PORTAL_USER, NETCFG_PORTAL_USER_MAX - 1 },
    { NETCFG_KEY_PORTAL_PASS, NETCFG_PORTAL_PASS_MAX - 1 },
    { NETCFG_KEY_SERVER_URL,  NETCFG_SERVER_URL_MAX - 1 },
    { NETCFG_KEY_SESSION_SEC, NETCFG_SESSION_MAX - 1 },
    { NETCFG_KEY_USER_ID,     NETCFG_USER_ID_MAX - 1 },
    { NETCFG_KEY_IDENTITY,    NETCFG_IDENTITY_MAX - 1 },
};

static char *field_ptr(net_config_t *cfg, const char *key)
{
    if (strcmp(key, NETCFG_KEY_WIFI_SSID) == 0)   return cfg->wifi_ssid;
    if (strcmp(key, NETCFG_KEY_WIFI_PASS) == 0)   return cfg->wifi_pass;
    if (strcmp(key, NETCFG_KEY_PORTAL_USER) == 0) return cfg->portal_user;
    if (strcmp(key, NETCFG_KEY_PORTAL_PASS) == 0) return cfg->portal_pass;
    if (strcmp(key, NETCFG_KEY_SERVER_URL) == 0)  return cfg->server_url;
    if (strcmp(key, NETCFG_KEY_SESSION_SEC) == 0) return cfg->session_secret;
    if (strcmp(key, NETCFG_KEY_USER_ID) == 0)     return cfg->user_id;
    if (strcmp(key, NETCFG_KEY_IDENTITY) == 0)    return cfg->identity_code;
    return NULL;
}

void netcfg_nvs_init(void)
{
    esp_err_t err = nvs_open(NETCFG_NAMESPACE, NVS_READWRITE, &s_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nvs_open failed: %s", esp_err_to_name(err));
        s_handle = 0;
    }
}

bool netcfg_load(net_config_t *out)
{
    if (!s_handle) return false;
    memset(out, 0, sizeof(*out));

    for (size_t i = 0; i < sizeof(s_fields) / sizeof(s_fields[0]); i++) {
        size_t sz = s_fields[i].max + 1;
        char tmp[NETCFG_SERVER_URL_MAX + 1];  // largest field + NUL
        esp_err_t err = nvs_get_str(s_handle, s_fields[i].key, tmp, &sz);
        if (err == ESP_OK) {
            strlcpy(field_ptr(out, s_fields[i].key), tmp, s_fields[i].max + 1);
            out->present = true;
        }
    }
    return true;
}

bool netcfg_save(const net_config_t *cfg)
{
    if (!s_handle) return false;

    esp_err_t err = ESP_OK;
    for (size_t i = 0; i < sizeof(s_fields) / sizeof(s_fields[0]); i++) {
        const char *val = field_ptr((net_config_t *)cfg, s_fields[i].key);
        err = nvs_set_str(s_handle, s_fields[i].key, val ? val : "");
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "nvs_set_str(%s) failed: %s", s_fields[i].key, esp_err_to_name(err));
            return false;
        }
    }
    err = nvs_commit(s_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nvs_commit failed: %s", esp_err_to_name(err));
        return false;
    }
    return true;
}

bool netcfg_set_field(const char *key, const char *value)
{
    if (!s_handle || !value) return false;

    size_t max = 0;
    bool known = false;
    for (size_t i = 0; i < sizeof(s_fields) / sizeof(s_fields[0]); i++) {
        if (strcmp(key, s_fields[i].key) == 0) {
            max = s_fields[i].max;
            known = true;
            break;
        }
    }
    if (!known) return false;
    if (strlen(value) > max) {
        ESP_LOGW(TAG, "value for %s too long (%d > %d)", key, (int)strlen(value), (int)max);
        return false;
    }

    net_config_t cfg;
    netcfg_load(&cfg);
    strlcpy(field_ptr(&cfg, key), value, max + 1);
    cfg.present = true;
    return netcfg_save(&cfg);
}

int64_t netcfg_get_last_ts(void)
{
    if (!s_handle) return 0;
    int64_t ts = 0;
    nvs_get_i64(s_handle, "last_ts", &ts);
    return ts;
}

void netcfg_set_last_ts(int64_t ts)
{
    if (!s_handle) return;
    if (nvs_set_i64(s_handle, "last_ts", ts) == ESP_OK) {
        nvs_commit(s_handle);
    }
}
