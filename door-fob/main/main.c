#include <stdio.h>
#include <string.h>
#include <inttypes.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include "driver/gpio.h"
#include "nvs_flash.h"
#include "door_crypto.h"
#include "door_ble.h"
#include "cred_store.h"
#include "config_service.h"
#include "net_config.h"
#include "net_service.h"
#include "door_api.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#define TAG "door-fob"

#define BTN_GPIO    GPIO_NUM_9
#define LED_GPIO    GPIO_NUM_8
#define LONG_PRESS_MS 3000
#define SYNC_PRESS_MS 1000

static door_config_t s_config = {
    .device_id  = 2283914,
    .project_id = 21048,
    .ble_mac    = "74:6E:00:22:D9:8A",
};
static bool s_config_loaded = false;

static void set_led(int on) {
    gpio_set_level(LED_GPIO, on ? 1 : 0);
}

static void led_blink(int times, int ms) {
    for (int i = 0; i < times; i++) {
        set_led(1);
        vTaskDelay(pdMS_TO_TICKS(ms));
        set_led(0);
        if (i < times - 1) vTaskDelay(pdMS_TO_TICKS(ms));
    }
}

static int measure_hold(void) {
    int ms = 0;
    while (gpio_get_level(BTN_GPIO) == 0) {
        vTaskDelay(pdMS_TO_TICKS(10));
        ms += 10;
    }
    return ms;
}

static void nimble_host_task(void *param) {
    nimble_port_run();
    nimble_port_freertos_deinit();
}

// Reload s_config from NVS (after BLE config or a network sync).
// Returns true when a stored credential was loaded.
static bool reload_config(void) {
    stored_credential_t stored;
    if (!cred_load(&stored)) return false;
    s_config.device_id = stored.device_id;
    s_config.project_id = stored.project_id;
    memcpy(s_config.credential, stored.credential, 32);
    strncpy(s_config.ble_mac, stored.ble_mac, sizeof(s_config.ble_mac) - 1);
    s_config.credential_id = stored.credential_id;
    s_config_loaded = true;
    return true;
}

// ── Network credential sync ────────────────────────────────────────────────
// Runs in a dedicated task (TLS needs a big stack); app_main blocks on a
// semaphore until it finishes.

typedef struct {
    bool ok;
    api_err_t api;
    stored_credential_t cred;
    SemaphoreHandle_t done;
} sync_job_t;

static void sync_task(void *arg) {
    sync_job_t *job = arg;
    net_err_t nerr = net_bring_up();
    if (nerr == NET_OK) {
        job->api = door_api_refresh_credential(&job->cred);
        job->ok = (job->api == API_OK);
    } else {
        job->api = API_ERR_HTTP;
    }
    net_tear_down();
    xSemaphoreGive(job->done);
    vTaskDelete(NULL);
}

// Returns true and fills *out on success; *err reports the failure reason.
static bool do_net_sync(stored_credential_t *out, api_err_t *err) {
    ESP_LOGI(TAG, "network sync started");
    sync_job_t job = { .api = API_ERR_HTTP, .done = xSemaphoreCreateBinary() };
    if (!job.done) return false;
    if (xTaskCreate(sync_task, "net-sync", 20480, &job, 5, NULL) != pdPASS) {
        vSemaphoreDelete(job.done);
        return false;
    }
    xSemaphoreTake(job.done, portMAX_DELAY);
    vSemaphoreDelete(job.done);

    *err = job.api;
    if (job.ok) memcpy(out, &job.cred, sizeof(*out));
    ESP_LOGI(TAG, "network sync finished: %s", door_api_err_str(job.api));
    return job.ok;
}

// LED feedback after a sync attempt: 3x300 ok, 10x100 needs re-export, 5x100 else
static void sync_report(bool ok, api_err_t err) {
    if (ok) {
        led_blink(3, 300);
    } else if (err == API_ERR_AUTH) {
        led_blink(10, 100);
    } else {
        led_blink(5, 100);
    }
}

void app_main(void) {
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    // GPIO
    gpio_config_t btn_conf = {
        .pin_bit_mask = (1ULL << BTN_GPIO),
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&btn_conf);

    gpio_config_t led_conf = {
        .pin_bit_mask = (1ULL << LED_GPIO),
        .mode = GPIO_MODE_OUTPUT,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&led_conf);
    set_led(0);

    // Credential store
    cred_nvs_init();
    netcfg_nvs_init();

    if (!reload_config()) {
        ESP_LOGI(TAG, "no credential in NVS, using hardcoded default");
        const char *cred_hex = "4A4BC7943CFC0E0BF65D334EFC00D830470CEA32988D6482E45C48EE1D63D41C";
        parse_credential_hex(cred_hex, s_config.credential);
        stored_credential_t def = {
            .device_id = s_config.device_id,
            .project_id = s_config.project_id,
        };
        memcpy(def.credential, s_config.credential, 32);
        strncpy(def.ble_mac, s_config.ble_mac, sizeof(def.ble_mac) - 1);
        cred_save(&def);
    }

    ESP_LOGI(TAG, "device_id=%" PRId32 " mac=%s", s_config.device_id, s_config.ble_mac);
    ESP_LOGI(TAG, "Short press = open, Mid press (1-3s) = net sync, Long press (3s) = config");

    // Init NimBLE once - register both central and peripheral services
    nimble_port_init();
    door_ble_init();
    config_service_init();
    nimble_port_freertos_init(nimble_host_task);
    vTaskDelay(pdMS_TO_TICKS(1500));  // wait for BLE sync

    while (1) {
        if (gpio_get_level(BTN_GPIO) == 0) {
            vTaskDelay(pdMS_TO_TICKS(50));
            if (gpio_get_level(BTN_GPIO) != 0) continue;

            int hold_ms = measure_hold();

            if (hold_ms >= LONG_PRESS_MS) {
                // Long press -> config mode
                ESP_LOGI(TAG, "Long press -> config mode");
                led_blink(5, 100);
                set_led(1);
                config_service_start();

                // Wait 60s for config (6000 units of 10ms)
                int timeout = 6000;
                while (timeout > 0 && config_service_active()) {
                    vTaskDelay(pdMS_TO_TICKS(10));
                    timeout -= 1;
                }
                config_service_stop();
                set_led(0);

                // Reload credential + network config
                reload_config();
                led_blink(3, 300);
                ESP_LOGI(TAG, "config updated");
            } else if (hold_ms >= SYNC_PRESS_MS) {
                // Mid press -> refresh credential over the network
                ESP_LOGI(TAG, "Mid press -> network sync");
                set_led(1);
                stored_credential_t fresh;
                api_err_t err;
                bool ok = do_net_sync(&fresh, &err);
                set_led(0);
                if (ok) {
                    if (cred_save(&fresh)) {
                        reload_config();
                        ESP_LOGI(TAG, "credential updated via network");
                    } else {
                        ESP_LOGE(TAG, "failed to persist synced credential");
                    }
                }
                sync_report(ok, err);
            } else {
                // Short press -> open door (auto net-sync + one retry on
                // credential-related failures)
                ESP_LOGI(TAG, "Button pressed, opening door...");
                set_led(1);

                bool refreshed = false;
                door_open_result_t result = door_ble_open(&s_config, &refreshed);

                if (refreshed) {
                    // Persist the rolling credential the lock just handed us,
                    // so the next press starts from the fresh key.
                    stored_credential_t upd = {0};
                    upd.device_id = s_config.device_id;
                    upd.project_id = s_config.project_id;
                    memcpy(upd.credential, s_config.credential, 32);
                    strncpy(upd.ble_mac, s_config.ble_mac, sizeof(s_config.ble_mac) - 1);
                    upd.credential_id = s_config.credential_id;
                    if (cred_save(&upd)) {
                        ESP_LOGI(TAG, "persisted refreshed credential");
                    } else {
                        ESP_LOGW(TAG, "failed to persist refreshed credential");
                    }
                }

                // Credential rejected by the lock/server -> try a network
                // refresh once, then retry the open.
                if (result == DOOR_OPEN_ERR_CRED_REFRESH || result == DOOR_OPEN_ERR_OPEN) {
                    ESP_LOGW(TAG, "open failed (%d), attempting network sync + retry", result);
                    stored_credential_t fresh;
                    api_err_t err;
                    if (do_net_sync(&fresh, &err)) {
                        if (cred_save(&fresh)) {
                            reload_config();
                            result = door_ble_open(&s_config, &refreshed);
                            if (refreshed) {
                                stored_credential_t upd = {0};
                                upd.device_id = s_config.device_id;
                                upd.project_id = s_config.project_id;
                                memcpy(upd.credential, s_config.credential, 32);
                                strncpy(upd.ble_mac, s_config.ble_mac, sizeof(s_config.ble_mac) - 1);
                                upd.credential_id = s_config.credential_id;
                                cred_save(&upd);
                            }
                        }
                    } else {
                        // Distinguish "session rejected" (needs app re-export)
                        // from generic sync failure in the final LED report.
                        if (err == API_ERR_NO_SESSION || err == API_ERR_AUTH) {
                            result = DOOR_OPEN_ERR_CRED_REFRESH;
                        }
                    }
                }

                switch (result) {
                case DOOR_OPEN_OK:
                    ESP_LOGI(TAG, "OPEN SUCCESS");
                    led_blink(3, 200);
                    break;
                case DOOR_OPEN_ERR_CRED_REFRESH:
                    ESP_LOGW(TAG, "CREDENTIAL NEEDS REFRESH");
                    led_blink(10, 100);
                    break;
                default:
                    ESP_LOGE(TAG, "OPEN FAILED: %d", result);
                    led_blink(5, 100);
                    break;
                }
                set_led(0);
            }

            while (gpio_get_level(BTN_GPIO) == 0) vTaskDelay(pdMS_TO_TICKS(50));
            vTaskDelay(pdMS_TO_TICKS(300));
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }
}
