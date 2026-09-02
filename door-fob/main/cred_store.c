#include "cred_store.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"
#include <string.h>

#define TAG "cred-store"
#define NVS_NAMESPACE "door-fob"

static nvs_handle_t s_nvs = 0;

void cred_nvs_init(void) {
    esp_err_t err = nvs_open(NVS_NAMESPACE, NVS_READWRITE, &s_nvs);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nvs_open failed: %d", err);
    }
}

bool cred_load(stored_credential_t *out) {
    if (s_nvs == 0) return false;

    size_t len = 0;
    uint32_t marker = 0;
    esp_err_t err = nvs_get_u32(s_nvs, "marker", &marker);
    if (err != ESP_OK || marker != 0x444F4F52) {  // "DOOR"
        ESP_LOGI(TAG, "no valid credential stored");
        return false;
    }

    int32_t did = 0, pid = 0;
    nvs_get_i32(s_nvs, "device_id", &did);
    nvs_get_i32(s_nvs, "project_id", &pid);

    len = 32;
    nvs_get_blob(s_nvs, "credential", out->credential, &len);

    len = 18;
    nvs_get_str(s_nvs, "ble_mac", out->ble_mac, &len);

    uint32_t cid = 0;
    nvs_get_u32(s_nvs, "cred_id", &cid);

    out->device_id = did;
    out->project_id = pid;
    out->credential_id = cid;
    out->valid = true;

    ESP_LOGI(TAG, "loaded: device_id=%" PRId32 " project_id=%" PRId32 " mac=%s",
             did, pid, out->ble_mac);
    return true;
}

bool cred_save(const stored_credential_t *cred) {
    if (s_nvs == 0) return false;

    esp_err_t err;
    err = nvs_set_u32(s_nvs, "marker", 0x444F4F52);
    if (err != ESP_OK) return false;

    err = nvs_set_i32(s_nvs, "device_id", cred->device_id);
    if (err != ESP_OK) return false;

    err = nvs_set_i32(s_nvs, "project_id", cred->project_id);
    if (err != ESP_OK) return false;

    err = nvs_set_blob(s_nvs, "credential", cred->credential, 32);
    if (err != ESP_OK) return false;

    err = nvs_set_str(s_nvs, "ble_mac", cred->ble_mac);
    if (err != ESP_OK) return false;

    err = nvs_set_u32(s_nvs, "cred_id", cred->credential_id);
    if (err != ESP_OK) return false;

    err = nvs_commit(s_nvs);
    if (err != ESP_OK) return false;

    ESP_LOGI(TAG, "saved: device_id=%" PRId32 " project_id=%" PRId32,
             cred->device_id, cred->project_id);
    return true;
}

void cred_erase(void) {
    if (s_nvs == 0) return;
    nvs_erase_all(s_nvs);
    nvs_commit(s_nvs);
    ESP_LOGI(TAG, "credential erased");
}
