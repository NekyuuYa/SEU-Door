#include "door_ble.h"
#include "door_crypto.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "host/ble_hs.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include <string.h>
#include <inttypes.h>

#define TAG "door-ble"

static const ble_uuid128_t DOOR_SERVICE_UUID =
    BLE_UUID128_INIT(0xfb, 0x34, 0x9b, 0x5f, 0x80, 0x00, 0x00, 0x80,
                      0x00, 0x10, 0x00, 0x00, 0x12, 0xff, 0x00, 0x00);
static const ble_uuid128_t DOOR_WRITE_UUID =
    BLE_UUID128_INIT(0xfb, 0x34, 0x9b, 0x5f, 0x80, 0x00, 0x00, 0x80,
                      0x00, 0x10, 0x00, 0x00, 0x01, 0xff, 0x00, 0x00);
static const ble_uuid128_t DOOR_READ_UUID =
    BLE_UUID128_INIT(0xfb, 0x34, 0x9b, 0x5f, 0x80, 0x00, 0x00, 0x80,
                      0x00, 0x10, 0x00, 0x00, 0x02, 0xff, 0x00, 0x00);

#define EVT_CONNECTED    BIT0
#define EVT_DISCOVERY    BIT1
#define EVT_READ_DONE    BIT2
#define EVT_SCAN_DONE    BIT3
#define EVT_WRITE_DONE   BIT4

static EventGroupHandle_t s_evt;
static int s_conn_handle = -1;
static uint16_t s_write_val_handle = 0;
static uint16_t s_read_val_handle = 0;
static uint8_t  s_read_props = 0;      // read char properties (NOTIFY/INDICATE)
static uint16_t s_cccd_handle = 0;     // 0x2902 descriptor of the read char
static uint16_t s_ff12_end = 0;        // end handle of the 0xFF12 service
static uint8_t s_response_buf[BLE_FRAME_SIZE];
static bool s_response_ready = false;
static int s_device_id = 0;

// Scan result: store found address
static ble_addr_t s_found_addr;
static bool s_found = false;

static void ble_app_on_sync(void) {
    ESP_LOGI(TAG, "BLE host synced");
}

// ---- GAP event handler ----

static int ble_gap_event(struct ble_gap_event *event, void *arg) {
    switch (event->type) {
    case BLE_GAP_EVENT_DISC: {
        struct ble_hs_adv_fields fields;
        ble_hs_adv_parse_fields(&fields, event->disc.data, event->disc.length_data);
        if (fields.name != NULL && fields.name_len > 0) {
            // Locks advertise as "XN-<device_id>". There can be several XN-
            // locks in range (neighbours), so only accept the one whose
            // device_id matches our target (s_device_id).
            if (fields.name_len > 3 && memcmp(fields.name, "XN-", 3) == 0) {
                char nm[24];
                int nl = fields.name_len < (int)sizeof(nm) - 1
                             ? fields.name_len : (int)sizeof(nm) - 1;
                memcpy(nm, fields.name, nl);
                nm[nl] = 0;
                bool matches = false;
                if (s_device_id > 0) {
                    char prefix[20];
                    snprintf(prefix, sizeof(prefix), "XN-%d", s_device_id);
                    // exact "XN-<id>" or id followed by a suffix ("XN-<id>-...")
                    if (strncmp(nm, prefix, strlen(prefix)) == 0) {
                        matches = true;
                    }
                }
                ESP_LOGI(TAG, "seen lock: %s rssi=%d %s",
                         nm, event->disc.rssi, matches ? "MATCH" : "skip");
                if (matches) {
                    s_found_addr = event->disc.addr;
                    s_found = true;
                    xEventGroupSetBits(s_evt, EVT_SCAN_DONE);
                }
            }
        }
        return 0;
    }
    case BLE_GAP_EVENT_CONNECT:
        ESP_LOGI(TAG, "connect %s; status=%d",
                 event->connect.status == 0 ? "established" : "failed",
                 event->connect.status);
        if (event->connect.status == 0) {
            s_conn_handle = event->connect.conn_handle;
            xEventGroupSetBits(s_evt, EVT_CONNECTED);
        }
        return 0;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnect; reason=%d", event->disconnect.reason);
        if (event->disconnect.conn.conn_handle == s_conn_handle) {
            s_conn_handle = -1;
        }
        return 0;
    case BLE_GAP_EVENT_NOTIFY_RX:
        if (OS_MBUF_PKTLEN(event->notify_rx.om) >= BLE_FRAME_SIZE) {
            os_mbuf_copydata(event->notify_rx.om, 0, BLE_FRAME_SIZE, s_response_buf);
            s_response_ready = true;
            xEventGroupSetBits(s_evt, EVT_READ_DONE);
        }
        return 0;
    default:
        return 0;
    }
}

static int ble_gatt_write_cb(uint16_t conn_handle,
                             const struct ble_gatt_error *error,
                             struct ble_gatt_attr *attr, void *arg)
{
    if (error->status == 0) {
        ESP_LOGI(TAG, "write ok");
    } else {
        ESP_LOGE(TAG, "write error: %d", error->status);
    }
    xEventGroupSetBits(s_evt, EVT_WRITE_DONE);
    return 0;
}

// ---- GATT discovery callbacks ----

static int ble_disc_dsc_cb(uint16_t conn_handle,
                           const struct ble_gatt_error *error,
                           uint16_t chr_val_handle,
                           const struct ble_gatt_dsc *dsc,
                           void *arg);

static char s_seen_svcs[160];   // services observed during this discovery
static bool s_chr_started;      // 0xFF12 found and its chars discovery launched

static bool uuid_is_ff12(const ble_uuid_any_t *uuid)
{
    if (uuid->u.type == BLE_UUID_TYPE_16)
        return uuid->u16.value == 0xFF12;
    if (uuid->u.type == BLE_UUID_TYPE_128)
        return memcmp(uuid->u128.value, DOOR_SERVICE_UUID.value, 16) == 0;
    return false;
}

static int ble_disc_chr_cb(uint16_t conn_handle,
                           const struct ble_gatt_error *error,
                           const struct ble_gatt_chr *chr,
                           void *arg);

static int ble_disc_svc_cb(uint16_t conn_handle,
                           const struct ble_gatt_error *error,
                           const struct ble_gatt_svc *svc,
                           void *arg) {
    if (error->status == BLE_HS_EDONE) {
        // Only fail here if we never found 0xFF12: when the target service
        // was found, its characteristic discovery is still in flight (its own
        // EDONE signals completion), so do not preempt it.
        if (!s_chr_started && s_write_val_handle == 0) {
            ESP_LOGE(TAG, "0xFF12 service absent; seen services: %s", s_seen_svcs);
            xEventGroupSetBits(s_evt, EVT_DISCOVERY);
        }
        return 0;
    }
    if (error->status != 0) {
        ESP_LOGE(TAG, "disc svc error: %d", error->status);
        xEventGroupSetBits(s_evt, EVT_DISCOVERY);
        return 0;
    }
    // record what this peer exposes (16-bit or last word of 128-bit)
    char tag[16];
    if (svc->uuid.u.type == BLE_UUID_TYPE_16) {
        snprintf(tag, sizeof(tag), "%04x", svc->uuid.u16.value);
    } else {
        snprintf(tag, sizeof(tag), "128:%02x%02x",
                 svc->uuid.u128.value[14], svc->uuid.u128.value[15]);
    }
    size_t sl = strlen(s_seen_svcs);
    int w = snprintf(s_seen_svcs + sl, sizeof(s_seen_svcs) - sl, "%s%s",
                     sl ? "," : "", tag);
    if (w < 0 || (size_t)w >= sizeof(s_seen_svcs) - sl) {
        /* truncate silently */
    }
    ESP_LOGI(TAG, "peer service %s (0x%04x-0x%04x)", tag,
             svc->start_handle, svc->end_handle);

    if (uuid_is_ff12(&svc->uuid)) {
        s_chr_started = true;
        s_ff12_end = svc->end_handle;
        int rc = ble_gattc_disc_all_chrs(conn_handle, svc->start_handle,
                                          svc->end_handle, ble_disc_chr_cb, NULL);
        if (rc != 0) {
            ESP_LOGE(TAG, "disc chr failed: %d", rc);
            xEventGroupSetBits(s_evt, EVT_DISCOVERY);
        }
    }
    return 0;
}

// Match a discovered characteristic against our reference: the lock may
// declare 16-bit UUIDs (0xFF01/0xFF02) while our constants are 128-bit
// base-form; ble_uuid_cmp does not normalize 16->128, so compare by kind.
static bool chr_is_uuid(const ble_uuid_any_t *uuid, uint16_t u16,
                        const ble_uuid128_t *ref128)
{
    if (uuid->u.type == BLE_UUID_TYPE_16)
        return uuid->u16.value == u16;
    if (uuid->u.type == BLE_UUID_TYPE_128)
        return memcmp(uuid->u128.value, ref128->value, 16) == 0;
    return false;
}

static int ble_disc_chr_cb(uint16_t conn_handle,
                           const struct ble_gatt_error *error,
                           const struct ble_gatt_chr *chr,
                           void *arg) {
    if (error->status == BLE_HS_EDONE) {
        if (s_read_val_handle == 0 || s_write_val_handle == 0) {
            ESP_LOGE(TAG, "missing chars: write=0x%04x read=0x%04x",
                     s_write_val_handle, s_read_val_handle);
            xEventGroupSetBits(s_evt, EVT_DISCOVERY);
            return 0;
        }
        // Locate the CCCD (0x2902) of the response characteristic so we can
        // subscribe to its notifications (the lock answers via notify).
        // Scan the whole 0xFF12 service range - descriptors belong to the
        // preceding characteristic regardless of char boundaries.
        int rc = ble_gattc_disc_all_dscs(conn_handle, s_read_val_handle,
                                         s_ff12_end, ble_disc_dsc_cb, NULL);
        if (rc != 0) {
            ESP_LOGE(TAG, "disc dsc start failed: %d", rc);
            xEventGroupSetBits(s_evt, EVT_DISCOVERY);
        }
        return 0;
    }
    if (error->status != 0) {
        ESP_LOGE(TAG, "disc chr error: %d", error->status);
        xEventGroupSetBits(s_evt, EVT_DISCOVERY);
        return 0;
    }
    if (chr_is_uuid(&chr->uuid, 0xFF01, &DOOR_WRITE_UUID)) {
        s_write_val_handle = chr->val_handle;
        ESP_LOGI(TAG, "found write chr: handle=0x%04x props=0x%02x",
                 s_write_val_handle, chr->properties);
    } else if (chr_is_uuid(&chr->uuid, 0xFF02, &DOOR_READ_UUID)) {
        s_read_val_handle = chr->val_handle;
        s_read_props = chr->properties;
        ESP_LOGI(TAG, "found read chr: handle=0x%04x props=0x%02x",
                 s_read_val_handle, chr->properties);
    }
    return 0;
}

// Descriptor discovery: find the CCCD (0x2902) of the read characteristic.
static int ble_disc_dsc_cb(uint16_t conn_handle,
                           const struct ble_gatt_error *error,
                           uint16_t chr_val_handle,
                           const struct ble_gatt_dsc *dsc,
                           void *arg) {
    if (error->status == BLE_HS_EDONE) {
        ESP_LOGI(TAG, "desc discovery done: cccd=0x%04x", s_cccd_handle);
        xEventGroupSetBits(s_evt, EVT_DISCOVERY);
        return 0;
    }
    if (error->status != 0) {
        ESP_LOGE(TAG, "disc dsc error: %d", error->status);
        xEventGroupSetBits(s_evt, EVT_DISCOVERY);
        return 0;
    }
    // log every descriptor we see (type tells us if the CCCD is 16- or 128-bit)
    {
        char tag[24];
        if (dsc->uuid.u.type == BLE_UUID_TYPE_16) {
            snprintf(tag, sizeof(tag), "%04x", dsc->uuid.u16.value);
        } else {
            snprintf(tag, sizeof(tag), "128:%02x%02x%02x%02x",
                     dsc->uuid.u128.value[12], dsc->uuid.u128.value[13],
                     dsc->uuid.u128.value[14], dsc->uuid.u128.value[15]);
        }
        ESP_LOGI(TAG, "desc 0x%04x uuid=%s", dsc->handle, tag);
    }
    if (dsc->uuid.u.type == BLE_UUID_TYPE_16 && dsc->uuid.u16.value == 0x2902) {
        s_cccd_handle = dsc->handle;
        ESP_LOGI(TAG, "found CCCD: handle=0x%04x", s_cccd_handle);
    }
    return 0;
}

// ---- BLE command send+receive ----

// The lock only accepts "write with response" on 0xFF01 and answers each
// command with a 20-byte notification on 0xFF02 (mirrors the Android app).
static bool ble_write_and_await(uint8_t cmd_type, const uint8_t data[BLE_DATA_SIZE],
                                ble_response_t *out_resp) {
    uint8_t frame[BLE_FRAME_SIZE];
    build_ble_command(frame, cmd_type, data, s_device_id);

    ESP_LOGI(TAG, "send cmd 0x%02x: %02x%02x%02x%02x...",
             cmd_type, frame[0], frame[1], frame[2], frame[3]);

    // Write WITH response (the lock rejects/handles differently otherwise)
    xEventGroupClearBits(s_evt, EVT_WRITE_DONE);
    int rc = ble_gattc_write_flat(s_conn_handle, s_write_val_handle,
                                  frame, BLE_FRAME_SIZE,
                                  ble_gatt_write_cb, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "write failed: %d", rc);
        return false;
    }
    EventBits_t bits = xEventGroupWaitBits(s_evt, EVT_WRITE_DONE,
                                           pdTRUE, pdFALSE,
                                           pdMS_TO_TICKS(2000));
    if (!(bits & EVT_WRITE_DONE)) {
        ESP_LOGE(TAG, "write ack timeout");
        return false;
    }

    // Await the lock's 20-byte notification reply
    s_response_ready = false;
    xEventGroupClearBits(s_evt, EVT_READ_DONE);
    bits = xEventGroupWaitBits(s_evt, EVT_READ_DONE,
                               pdTRUE, pdFALSE,
                               pdMS_TO_TICKS(3000));
    if (!(bits & EVT_READ_DONE) || !s_response_ready) {
        ESP_LOGE(TAG, "no response notification");
        return false;
    }

    *out_resp = parse_ble_response(s_response_buf, s_device_id);
    ESP_LOGI(TAG, "resp: cmd=0x%02x result=%d crc=%s ran=%" PRIu32,
             out_resp->command_type, out_resp->result_code,
             out_resp->crc_valid ? "OK" : "BAD", out_resp->ran);
    return true;
}

// ---- Init ----

void door_ble_init(void) {
    s_evt = xEventGroupCreate();
    ble_hs_cfg.sync_cb = ble_app_on_sync;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
}

// ---- Scan for door lock ----

static bool scan_for_door(int timeout_ms) {
    s_found = false;
    xEventGroupClearBits(s_evt, EVT_SCAN_DONE);

    struct ble_gap_disc_params disc_params = {0};
    disc_params.passive = 0;
    disc_params.itvl = 0;
    disc_params.window = 0;
    disc_params.filter_policy = 0;

    int rc = ble_gap_disc(0, timeout_ms, &disc_params, ble_gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "scan start failed: %d", rc);
        return false;
    }

    ESP_LOGI(TAG, "scanning for door lock (%d ms)...", timeout_ms);

    xEventGroupWaitBits(s_evt, EVT_SCAN_DONE,
                        pdTRUE, pdFALSE,
                        pdMS_TO_TICKS(timeout_ms + 500));

    // Stop scan
    ble_gap_disc_cancel();

    if (s_found) {
        ESP_LOGI(TAG, "door lock found!");
    } else {
        ESP_LOGW(TAG, "door lock not found");
    }
    return s_found;
}

// ---- Open sequence (header -> packets -> open) ----
// Runs over an already-established connection. Returns true if the full
// exchange completed at the transport level (in which case *out_result_code
// holds the lock's open result code); returns false on a transport-level
// failure, with *out_err set to the failing stage.

static bool do_open_sequence(const door_config_t *config, int *out_result_code,
                             door_open_result_t *out_err) {
    ble_response_t resp;

    // Step A: header (0x74)
    uint8_t header_data[BLE_DATA_SIZE];
    build_header_payload(header_data, config->project_id, config->credential);
    if (!ble_write_and_await(BLE_CMD_HEADER, header_data, &resp)) {
        *out_err = DOOR_OPEN_ERR_HEADER;
        return false;
    }
    if (resp.result_code != 0) {
        ESP_LOGE(TAG, "header rejected: code=%d", resp.result_code);
        *out_err = DOOR_OPEN_ERR_HEADER;
        return false;
    }
    uint32_t ran = resp.ran;
    ESP_LOGI(TAG, "got ran = %" PRIu32, ran);

    // Step B: credential packets (0x75 x3)
    uint8_t packets[3][BLE_DATA_SIZE];
    int pkt_count = build_credential_packets(packets, config->project_id, config->credential, ran);
    for (int i = 0; i < pkt_count; i++) {
        if (!ble_write_and_await(BLE_CMD_PACKET, packets[i], &resp)) {
            *out_err = DOOR_OPEN_ERR_PACKET;
            return false;
        }
        if (i < pkt_count - 1) vTaskDelay(pdMS_TO_TICKS(10));
    }
    vTaskDelay(pdMS_TO_TICKS(50));

    // Step C: open (0x78)
    uint8_t open_data[BLE_DATA_SIZE];
    build_open_payload(open_data);
    if (!ble_write_and_await(BLE_CMD_OPEN, open_data, &resp)) {
        *out_err = DOOR_OPEN_ERR_OPEN;
        return false;
    }

    *out_result_code = resp.result_code;
    return true;
}

// ---- Rolling-credential refresh (lock returned code 27) ----
// Mirrors the Android app: 0x76 refetch (with credential_id) -> read N packets
// via 0x77 -> reassemble + CRC8-verify the new 32-byte credential.

static bool refresh_credential(const door_config_t *config, uint8_t out_credential[32]) {
    if (config->credential_id == 0) {
        ESP_LOGE(TAG, "no credential_id, cannot refresh");
        return false;
    }

    ble_response_t resp;

    // Step 1: 0x76 refetch
    uint8_t refetch_data[BLE_DATA_SIZE];
    build_refetch_payload(refetch_data, config->credential_id);
    if (!ble_write_and_await(BLE_CMD_REFETCH, refetch_data, &resp)) return false;
    if (resp.command_type != BLE_CMD_REFETCH || !resp.crc_valid) {
        ESP_LOGE(TAG, "refetch response invalid (cmd=0x%02x crc=%d)",
                 resp.command_type, resp.crc_valid);
        return false;
    }
    if (resp.result_code != 0 && resp.result_code != 23) {
        ESP_LOGE(TAG, "refetch rejected: code=%d", resp.result_code);
        return false;
    }

    int packet_count = resp.plain_data[1];
    int cred_len = resp.plain_data[2] | (resp.plain_data[3] << 8);
    uint8_t expected_crc = resp.plain_data[4];
    if (packet_count <= 0 || packet_count > 3 || cred_len <= 0 || cred_len > 32) {
        ESP_LOGE(TAG, "bad refresh params: count=%d len=%d", packet_count, cred_len);
        return false;
    }

    // Step 2: read each packet via 0x77, place by reported index
    uint8_t merged[3 * 15] = {0};
    for (int i = 0; i < packet_count; i++) {
        uint8_t read_data[BLE_DATA_SIZE];
        build_packet_read_payload(read_data, (uint8_t)i);
        if (!ble_write_and_await(BLE_CMD_PACKET_READ, read_data, &resp)) return false;
        if (resp.command_type != BLE_CMD_PACKET_READ || !resp.crc_valid) {
            ESP_LOGE(TAG, "packet-read response invalid (cmd=0x%02x crc=%d)",
                     resp.command_type, resp.crc_valid);
            return false;
        }
        int idx = resp.plain_data[0];
        if (idx < 0 || idx >= packet_count) {
            ESP_LOGE(TAG, "packet index out of range: %d", idx);
            return false;
        }
        memcpy(&merged[idx * 15], &resp.plain_data[1], 15);
        if (i < packet_count - 1) vTaskDelay(pdMS_TO_TICKS(10));
    }

    // Step 3: verify CRC8 over the reassembled credential
    uint8_t actual_crc = crc8(merged, cred_len);
    if (actual_crc != expected_crc) {
        ESP_LOGE(TAG, "refreshed credential CRC mismatch (got=0x%02x want=0x%02x)",
                 actual_crc, expected_crc);
        return false;
    }

    memcpy(out_credential, merged, 32);
    ESP_LOGI(TAG, "credential refreshed (len=%d)", cred_len);
    return true;
}

// ---- Open Door ----

door_open_result_t door_ble_open(door_config_t *config, bool *out_refreshed) {
    if (out_refreshed) *out_refreshed = false;
    s_device_id = config->device_id;
    s_conn_handle = -1;
    s_write_val_handle = 0;
    s_read_val_handle = 0;
    s_read_props = 0;
    s_cccd_handle = 0;
    s_ff12_end = 0;

    // Step 1: Scan for door lock
    if (!scan_for_door(8000)) {
        // Fallback: try direct connect by MAC
        ESP_LOGW(TAG, "scan failed, trying direct connect...");
        ble_addr_t addr;
        uint8_t mac[6];
        int parsed = sscanf(config->ble_mac, "%hhx:%hhx:%hhx:%hhx:%hhx:%hhx",
                            &mac[0], &mac[1], &mac[2], &mac[3], &mac[4], &mac[5]);
        if (parsed != 6) {
            ESP_LOGE(TAG, "invalid MAC: %s", config->ble_mac);
            return DOOR_OPEN_ERR_CONNECT;
        }
        for (int i = 0; i < 6; i++) addr.val[i] = mac[5 - i];
        addr.type = BLE_ADDR_PUBLIC;
        s_found_addr = addr;
    }

    // Wait for door lock to return to connectable state
    vTaskDelay(pdMS_TO_TICKS(100));

    // Step 2: Connect with specific params
    ESP_LOGI(TAG, "connecting...");
    xEventGroupClearBits(s_evt, EVT_CONNECTED);

    struct ble_gap_conn_params conn_params = {0};
    // scan_itvl/scan_window are REQUIRED by LE Create Connection (>=0x0004);
    // leaving them 0 makes the controller reject with HCI 0x12 (rc=530).
    conn_params.scan_itvl = 0x0010;   // ~10ms
    conn_params.scan_window = 0x0010;
    conn_params.itvl_min = 24;    // 15ms
    conn_params.itvl_max = 48;    // 30ms
    conn_params.latency = 0;
    conn_params.supervision_timeout = 500;  // 5s
    conn_params.min_ce_len = 0;
    conn_params.max_ce_len = 0;

    int rc = ble_gap_connect(0, &s_found_addr, 10000, &conn_params, ble_gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "connect init failed: %d", rc);
        return DOOR_OPEN_ERR_CONNECT;
    }

    EventBits_t bits = xEventGroupWaitBits(s_evt, EVT_CONNECTED,
                                            pdTRUE, pdFALSE,
                                            pdMS_TO_TICKS(12000));
    if (!(bits & EVT_CONNECTED) || s_conn_handle < 0) {
        ESP_LOGE(TAG, "connect timeout");
        return DOOR_OPEN_ERR_CONNECT;
    }
    ESP_LOGI(TAG, "connected handle=%d", s_conn_handle);
    {
        struct ble_gap_conn_desc desc;
        if (ble_gap_conn_find(s_conn_handle, &desc) == 0) {
            ESP_LOGI(TAG, "peer addr %02X:%02X:%02X:%02X:%02X:%02X",
                     desc.peer_ota_addr.val[0], desc.peer_ota_addr.val[1],
                     desc.peer_ota_addr.val[2], desc.peer_ota_addr.val[3],
                     desc.peer_ota_addr.val[4], desc.peer_ota_addr.val[5]);
        }
    }

    // Give the link a moment to settle before running GATT discovery (the
    // very first ATT exchanges can race right after the connect event).
    vTaskDelay(pdMS_TO_TICKS(200));

    // Step 3: Discover all services (we log the whole table so a wrong peer
    // or a gated service table is obvious)
    ESP_LOGI(TAG, "discovering service...");
    xEventGroupClearBits(s_evt, EVT_DISCOVERY);
    s_seen_svcs[0] = 0;
    s_chr_started = false;
    rc = ble_gattc_disc_all_svcs(s_conn_handle, ble_disc_svc_cb, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "disc svc start failed: %d", rc);
        return DOOR_OPEN_ERR_DISCOVERY;
    }

    bits = xEventGroupWaitBits(s_evt, EVT_DISCOVERY,
                                pdTRUE, pdFALSE,
                                pdMS_TO_TICKS(10000));
    if (!(bits & EVT_DISCOVERY) || s_write_val_handle == 0 || s_cccd_handle == 0) {
        ESP_LOGE(TAG, "discovery incomplete (write=0x%04x cccd=0x%04x)",
                 s_write_val_handle, s_cccd_handle);
        return DOOR_OPEN_ERR_DISCOVERY;
    }

    // Subscribe to the response characteristic's notifications: the lock
    // pushes its 20-byte replies here (mirrors the Android app's CCCD write).
    {
        uint8_t cccd_val[2];
        if (s_read_props & BLE_GATT_CHR_F_INDICATE) {
            cccd_val[0] = 0x02;  // indications
        } else {
            cccd_val[0] = 0x01;  // notifications
        }
        cccd_val[1] = 0x00;
        xEventGroupClearBits(s_evt, EVT_WRITE_DONE);
        rc = ble_gattc_write_flat(s_conn_handle, s_cccd_handle,
                                  cccd_val, sizeof(cccd_val),
                                  ble_gatt_write_cb, NULL);
        if (rc != 0) {
            ESP_LOGE(TAG, "cccd write failed: %d", rc);
            return DOOR_OPEN_ERR_DISCOVERY;
        }
        bits = xEventGroupWaitBits(s_evt, EVT_WRITE_DONE,
                                   pdTRUE, pdFALSE, pdMS_TO_TICKS(2000));
        if (!(bits & EVT_WRITE_DONE)) {
            ESP_LOGE(TAG, "cccd write timeout");
            return DOOR_OPEN_ERR_DISCOVERY;
        }
    }

    // Step 4: Run the open sequence (header -> packets -> open)
    door_open_result_t result;
    int result_code = -1;
    door_open_result_t stage_err = DOOR_OPEN_ERR_OPEN;
    if (!do_open_sequence(config, &result_code, &stage_err)) {
        result = stage_err;
        goto cleanup;
    }

    // Step 5: Rolling credential stale -> refresh and retry once
    if (result_code == 27) {
        ESP_LOGW(TAG, "credential stale (27), refreshing...");
        uint8_t new_cred[32];
        if (!refresh_credential(config, new_cred)) {
            result = DOOR_OPEN_ERR_CRED_REFRESH;
            goto cleanup;
        }
        memcpy(config->credential, new_cred, 32);
        if (out_refreshed) *out_refreshed = true;

        vTaskDelay(pdMS_TO_TICKS(50));
        if (!do_open_sequence(config, &result_code, &stage_err)) {
            result = stage_err;
            goto cleanup;
        }
    }

    ESP_LOGI(TAG, "open result code: %d", result_code);
    if (result_code == 0 || result_code == 23) {
        result = DOOR_OPEN_OK;
    } else if (result_code == 27) {
        result = DOOR_OPEN_ERR_CRED_REFRESH;
    } else {
        result = DOOR_OPEN_ERR_OPEN;
    }

cleanup:
    if (s_conn_handle >= 0) {
        ESP_LOGI(TAG, "terminating connection %d", s_conn_handle);
        ble_gap_terminate(s_conn_handle, BLE_ERR_REM_USER_CONN_TERM);
        // Wait until the host has processed the disconnect, otherwise the peer
        // stays in the connection table and the next open fails with EDONE.
        for (int i = 0; i < 50 && s_conn_handle >= 0; i++) {
            vTaskDelay(pdMS_TO_TICKS(20));
        }
        if (s_conn_handle >= 0) {
            ESP_LOGW(TAG, "disconnect not confirmed within 1s");
        }
    }
    return result;
}
