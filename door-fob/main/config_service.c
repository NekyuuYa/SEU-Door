#include "config_service.h"
#include "cred_store.h"
#include "net_config.h"
#include "esp_log.h"
#include "host/ble_hs.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <inttypes.h>

#define TAG "config-svc"

static const ble_uuid128_t CFG_SVC_UUID =
    BLE_UUID128_INIT(0xfb, 0x34, 0x9b, 0x5f, 0x80, 0x00, 0x00, 0x80,
                      0x00, 0x10, 0x00, 0x00, 0x10, 0xff, 0x00, 0x00);
static const ble_uuid128_t CFG_INFO_UUID =
    BLE_UUID128_INIT(0xfb, 0x34, 0x9b, 0x5f, 0x80, 0x00, 0x00, 0x80,
                      0x00, 0x10, 0x00, 0x00, 0x11, 0xff, 0x00, 0x00);
static const ble_uuid128_t CFG_WRITE_UUID =
    BLE_UUID128_INIT(0xfb, 0x34, 0x9b, 0x5f, 0x80, 0x00, 0x00, 0x80,
                      0x00, 0x10, 0x00, 0x00, 0x12, 0xff, 0x00, 0x00);
static const ble_uuid128_t CFG_STATUS_UUID =
    BLE_UUID128_INIT(0xfb, 0x34, 0x9b, 0x5f, 0x80, 0x00, 0x00, 0x80,
                      0x00, 0x10, 0x00, 0x00, 0x13, 0xff, 0x00, 0x00);

static bool s_active = false;
static int s_conn_handle = -1;
static uint16_t s_status_val_handle = 0;

// ── JSON string decode (the App writes real JSON via org.json, which escapes
//    '/' as '\/' and quotes/backslashes; naive quote-slicing keeps those
//    escapes literally). Reads the string starting at *sp (the opening quote),
//    writes the unescaped bytes to out, and advances *sp past the closing
//    quote. Handles \" \\ \/ \b \f \n \r \t and \uXXXX (BMP, as UTF-8).
static bool json_read_string(const char **sp, char *out, size_t outsz)
{
    const char *p = *sp;
    if (*p != '"') return false;
    p++;
    size_t o = 0;
    while (*p && *p != '"') {
        char c = *p;
        if (c != '\\') {
            if (o + 1 >= outsz) return false;
            out[o++] = c;
            p++;
            continue;
        }
        p++;   // skip backslash
        if (!*p) return false;
        char e;
        switch (*p) {
        case '"':  e = '"';  p++; break;
        case '\\': e = '\\'; p++; break;
        case '/':  e = '/';  p++; break;
        case 'b':  e = '\b'; p++; break;
        case 'f':  e = '\f'; p++; break;
        case 'n':  e = '\n'; p++; break;
        case 'r':  e = '\r'; p++; break;
        case 't':  e = '\t'; p++; break;
        case 'u': {
            if (!p[1] || !p[2] || !p[3] || !p[4]) return false;
            unsigned cp = 0;
            for (int i = 1; i <= 4; i++) {
                char h = p[i];
                unsigned v;
                if      (h >= '0' && h <= '9') v = (unsigned)(h - '0');
                else if (h >= 'a' && h <= 'f') v = (unsigned)(h - 'a' + 10);
                else if (h >= 'A' && h <= 'F') v = (unsigned)(h - 'A' + 10);
                else return false;
                cp = (cp << 4) | v;
            }
            p += 5;
            if (cp < 0x80) {
                if (o + 1 >= outsz) return false;
                out[o++] = (char)cp;
            } else if (cp < 0x800) {
                if (o + 2 >= outsz) return false;
                out[o++] = (char)(0xC0 | (cp >> 6));
                out[o++] = (char)(0x80 | (cp & 0x3F));
            } else {
                if (o + 3 >= outsz) return false;
                out[o++] = (char)(0xE0 | (cp >> 12));
                out[o++] = (char)(0x80 | ((cp >> 6) & 0x3F));
                out[o++] = (char)(0x80 | (cp & 0x3F));
            }
            continue;
        }
        default:
            return false;
        }
        if (o + 1 >= outsz) return false;
        out[o++] = e;
    }
    if (*p != '"') return false;
    *sp = p + 1;
    out[o] = 0;
    return true;
}

// Locate ':' after a JSON member name then skip whitespace; on success leaves
// *vp pointing at the value (a '"' for strings).
static bool json_after_colon(const char *p, const char **vp)
{
    p = strchr(p, ':');
    if (!p) return false;
    p++;
    while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
    *vp = p;
    return true;
}

static int gatt_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                           struct ble_gatt_access_ctxt *ctxt, void *arg) {
    const ble_uuid_t *uuid = ctxt->chr->uuid;

    if (ble_uuid_cmp(uuid, &CFG_INFO_UUID.u) == 0) {
        uint8_t info[20];
        memset(info, 0, 20);
        uint32_t fw_ver = 1;
        memcpy(info, &fw_ver, 4);
        stored_credential_t cred;
        if (cred_load(&cred)) {
            memcpy(info + 4, &cred.device_id, 4);
            info[8] = 1;
        }
        os_mbuf_append(ctxt->om, info, 20);
        return 0;
    }

    if (ble_uuid_cmp(uuid, &CFG_WRITE_UUID.u) == 0) {
        uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
        if (len > 512) len = 512;

        char *buf = malloc(len + 1);
        if (!buf) return BLE_HS_ENOMEM;
        os_mbuf_copydata(ctxt->om, 0, len, (uint8_t *)buf);
        buf[len] = '\0';

        ESP_LOGI(TAG, "received config write: %d bytes", len);

        // Each write is a standalone JSON; only the keys it contains are
        // updated (field-wise partial update). Door credential fields go to
        // cred_store, network/session fields go to the net-cfg namespace.
        int status = 4;
        bool has_door_field = false;
        bool has_net_field = false;

        // ── door credential fields (merge over currently stored values) ──
        stored_credential_t cred = {0};
        bool have_stored = cred_load(&cred);
        int32_t new_device_id = cred.device_id;
        char new_ble_mac[18];
        strlcpy(new_ble_mac, cred.ble_mac, sizeof(new_ble_mac));
        uint32_t new_cred_id = cred.credential_id;
        int32_t new_project_id = cred.project_id;
        uint8_t new_credential[32];
        memcpy(new_credential, cred.credential, 32);
        bool credential_present = have_stored;
        char val_buf[NETCFG_IDENTITY_MAX + 1];   // largest net string field

        char *p = strstr(buf, "\"device_id\"");
        if (p) {
            has_door_field = true;
            p = strchr(p, ':');
            if (p) new_device_id = atoi(p + 1);
        }

        p = strstr(buf, "\"project_id\"");
        if (p) {
            has_door_field = true;
            p = strchr(p, ':');
            if (p) new_project_id = atoi(p + 1);
        }

        p = strstr(buf, "\"credential\"");
        if (p) {
            has_door_field = true;
            // Skip past "credential":" to reach the hex value
            p = strstr(p, ":\"");
            if (p) {
                p += 2;  // skip :"
                char *end = strchr(p, '\"');
                if (end && (end - p) == 64) {
                    for (int i = 0; i < 32; i++) {
                        uint8_t hi, lo;
                        char ch = p[i * 2];
                        if      (ch >= '0' && ch <= '9') hi = ch - '0';
                        else if (ch >= 'a' && ch <= 'f') hi = ch - 'a' + 10;
                        else if (ch >= 'A' && ch <= 'F') hi = ch - 'A' + 10;
                        else { status = 2; goto done; }
                        ch = p[i * 2 + 1];
                        if      (ch >= '0' && ch <= '9') lo = ch - '0';
                        else if (ch >= 'a' && ch <= 'f') lo = ch - 'a' + 10;
                        else if (ch >= 'A' && ch <= 'F') lo = ch - 'A' + 10;
                        else { status = 2; goto done; }
                        new_credential[i] = (hi << 4) | lo;
                    }
                    credential_present = true;
                } else { status = 2; goto done; }
            }
        }

        p = strstr(buf, "\"ble_mac\"");
        if (p) {
            has_door_field = true;
            const char *v = NULL;
            if (json_after_colon(p, &v) && json_read_string(&v, new_ble_mac,
                                                            sizeof(new_ble_mac))) {
                // ok, decoded
            } else {
                new_ble_mac[0] = 0;
            }
        }

        p = strstr(buf, "\"credential_id\"");
        if (p) {
            has_door_field = true;
            p = strchr(p, ':');
            if (p) new_cred_id = (uint32_t)atoi(p + 1);
        }

        if (has_door_field) {
            if (new_device_id == 0) { status = 3; goto done; }
            if (!credential_present) { status = 3; goto done; }
            cred.device_id = new_device_id;
            cred.project_id = new_project_id;
            memcpy(cred.credential, new_credential, 32);
            strlcpy(cred.ble_mac, new_ble_mac, sizeof(cred.ble_mac));
            cred.credential_id = new_cred_id;
            if (!cred_save(&cred)) goto done;   // status stays 4 on failure
        }

        // ── network / session fields (net-cfg namespace) ──
        static const char *net_keys[] = {
            NETCFG_KEY_WIFI_SSID, NETCFG_KEY_WIFI_PASS,
            NETCFG_KEY_PORTAL_USER, NETCFG_KEY_PORTAL_PASS,
            NETCFG_KEY_SERVER_URL, NETCFG_KEY_SESSION_SEC,
            NETCFG_KEY_USER_ID, NETCFG_KEY_IDENTITY,
        };
        for (int i = 0; i < (int)(sizeof(net_keys) / sizeof(net_keys[0])); i++) {
            char pat[40];
            snprintf(pat, sizeof(pat), "\"%s\"", net_keys[i]);
            p = strstr(buf, pat);
            if (!p) continue;
            has_net_field = true;
            const char *v = NULL;
            if (!json_after_colon(p, &v) ||
                !json_read_string(&v, val_buf, sizeof(val_buf))) {
                status = 1; goto done;
            }
            size_t vlen = strlen(val_buf);
            ESP_LOGI(TAG, "net field %s = %.24s%s", net_keys[i], val_buf,
                     vlen > 24 ? "..." : "");
            if (!netcfg_set_field(net_keys[i], val_buf)) goto done;
            // unknown key / too long / save failure -> 4
        }

        if (!has_door_field && !has_net_field) {
            status = 1;   // nothing we recognize
        } else {
            status = 0;
        }

done:
        free(buf);
        ESP_LOGI(TAG, "config result: %d", status);

        if (s_status_val_handle > 0 && s_conn_handle >= 0) {
            uint8_t sb = (uint8_t)status;
            struct os_mbuf *om = ble_hs_mbuf_from_flat(&sb, 1);
            if (om) ble_gattc_notify_custom(s_conn_handle, s_status_val_handle, om);
        }
        return 0;
    }

    if (ble_uuid_cmp(uuid, &CFG_STATUS_UUID.u) == 0) {
        uint8_t zero = 0;
        os_mbuf_append(ctxt->om, &zero, 1);
        return 0;
    }

    return BLE_ATT_ERR_UNLIKELY;
}

static const struct ble_gatt_svc_def gatt_svr_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &CFG_SVC_UUID.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            { .uuid = &CFG_INFO_UUID.u, .access_cb = gatt_access_cb, .flags = BLE_GATT_CHR_F_READ },
            { .uuid = &CFG_WRITE_UUID.u, .access_cb = gatt_access_cb, .flags = BLE_GATT_CHR_F_WRITE },
            { .uuid = &CFG_STATUS_UUID.u, .access_cb = gatt_access_cb,
              .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY, .val_handle = &s_status_val_handle },
            {0}
        },
    },
    {0}
};

static int cfg_gap_event(struct ble_gap_event *event, void *arg) {
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            s_conn_handle = event->connect.conn_handle;
            ESP_LOGI(TAG, "client connected: handle=%d", s_conn_handle);
        }
        return 0;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "client disconnected");
        s_conn_handle = -1;
        if (s_active) {
            config_service_start_adv();
        }
        return 0;
    case BLE_GAP_EVENT_SUBSCRIBE:
        return 0;
    default:
        return 0;
    }
}

void config_service_init(void) {
    ble_svc_gap_init();
    ble_svc_gatt_init();

    int rc = ble_gatts_count_cfg(gatt_svr_svcs);
    if (rc != 0) {
        ESP_LOGE(TAG, "gatts_count_cfg failed: %d", rc);
        return;
    }

    rc = ble_gatts_add_svcs(gatt_svr_svcs);
    if (rc != 0) {
        ESP_LOGE(TAG, "gatts_add_svcs failed: %d", rc);
        return;
    }

    ESP_LOGI(TAG, "config GATT services registered");
}

void config_service_start(void) {
    s_active = true;
    config_service_start_adv();
}

void config_service_start_adv(void) {
    struct ble_gap_adv_params adv_params = {0};
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    adv_params.itvl_min = 0x20;
    adv_params.itvl_max = 0x40;

    uint8_t svc_data[] = {0x03, 0x03, 0x10, 0xFF};
    ble_gap_adv_set_data(svc_data, sizeof(svc_data));

    int rc = ble_gap_adv_start(0, NULL, BLE_HS_FOREVER, &adv_params, cfg_gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "adv_start failed: %d", rc);
    } else {
        ESP_LOGI(TAG, "config mode advertising (0xFF10)");
    }
}

bool config_service_active(void) {
    return s_active;
}

void config_service_stop(void) {
    if (s_active) {
        ble_gap_adv_stop();
        s_active = false;
        ESP_LOGI(TAG, "config service stopped");
    }
}
