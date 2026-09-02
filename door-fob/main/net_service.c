#include "net_service.h"
#include "net_config.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_netif_sntp.h"
#include "esp_http_client.h"
#include "esp_crt_bundle.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "nvs_flash.h"
#include <string.h>
#include <stdio.h>
#include <time.h>

#define TAG "net-svc"

#define WIFI_CONNECT_TIMEOUT_MS 45000   // campus DHCP can be slow
#define SNTP_SYNC_TIMEOUT_MS    12000
#define PORTAL_TIMEOUT_MS       8000

// Treated as "clock is sane" (2020-09-17 ~ 2111)
#define TS_SANITY_MIN 1600000000LL

static EventGroupHandle_t s_events;
static esp_netif_t *s_sta_netif;
static bool s_wifi_started;
#define EV_GOT_IP BIT0

static void event_handler(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        esp_wifi_connect();
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *evt = (ip_event_got_ip_t *)data;
        ESP_LOGI(TAG, "got ip: " IPSTR, IP2STR(&evt->ip_info.ip));
        xEventGroupSetBits(s_events, EV_GOT_IP);
    }
}

static void wifi_lazy_init(void)
{
    static bool done;
    if (done) return;
    done = true;

    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    s_sta_netif = esp_netif_create_default_wifi_sta();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, &event_handler, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, &event_handler, NULL));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));

    if (!s_events) s_events = xEventGroupCreate();
}

// ── HTTPS helper (server certificates verified via the built-in CA bundle) ──

static esp_err_t http_get(const char *url, char *resp, size_t resp_size, int *status)
{
    esp_http_client_config_t cfg = {
        .url = url,
        .timeout_ms = PORTAL_TIMEOUT_MS,
        .buffer_size = 1024,
        .crt_bundle_attach = esp_crt_bundle_attach,
    };
    esp_http_client_handle_t client = esp_http_client_init(&cfg);
    if (!client) return ESP_FAIL;

    esp_err_t err = esp_http_client_open(client, 0);
    if (err != ESP_OK) {
        ESP_LOGD(TAG, "open %s failed: %s", url, esp_err_to_name(err));
        esp_http_client_cleanup(client);
        return err;
    }
    esp_http_client_fetch_headers(client);
    if (resp) {
        int n = esp_http_client_read_response(client, resp, resp_size - 1);
        resp[n > 0 ? n : 0] = 0;
    } else {
        char sink[256];
        while (esp_http_client_read_response(client, sink, sizeof(sink) - 1) > 0) {}
    }
    if (status) *status = esp_http_client_get_status_code(client);
    esp_http_client_close(client);
    esp_http_client_cleanup(client);
    return ESP_OK;
}

// ── SEU campus portal auth (drcom chkstatus -> eportal login) ──────────────
// portal_pass is inserted into the URL verbatim (same wire format as the
// user's proven shell script; `#` pre-encoded as `%23` etc.).

static bool portal_parse_v46ip(const char *json, char *out, size_t outlen)
{
    const char *key = "\"v46ip\":\"";
    const char *p = strstr(json, key);
    if (!p) return false;
    p += strlen(key);
    const char *e = strchr(p, '"');
    if (!e || (size_t)(e - p) >= outlen) return false;
    memcpy(out, p, e - p);
    out[e - p] = 0;
    return true;
}

static void portal_try_auth(const char *user, const char *pass)
{
    static char resp[1024];
    static char url[512];
    char ip[16];
    int status;

    esp_err_t err = http_get("https://w.seu.edu.cn/drcom/chkstatus?callback=dr1003",
                             resp, sizeof(resp), &status);
    if (err != ESP_OK) {
        // Not on the campus portal network (e.g. phone hotspot) — fine, skip.
        ESP_LOGI(TAG, "portal chkstatus unreachable (%s), skipping portal auth",
                 esp_err_to_name(err));
        return;
    }
    if (!portal_parse_v46ip(resp, ip, sizeof(ip))) {
        ESP_LOGW(TAG, "portal chkstatus: no v46ip (resp=%.120s)", resp);
        return;
    }
    ESP_LOGI(TAG, "portal IP (v46ip): %s", ip);

    snprintf(url, sizeof(url),
             "https://w.seu.edu.cn:801/eportal/?c=Portal&a=login&callback=dr1003&login_method=1"
             "&user_account=%%2C0%%2C%s&user_password=%s&wlan_user_ip=%s",
             user, pass, ip);
    err = http_get(url, resp, sizeof(resp), &status);
    ESP_LOGI(TAG, "portal login: err=%s status=%d resp=%.160s",
             esp_err_to_name(err), status, resp);
}

// ── Time ────────────────────────────────────────────────────────────────────

static void sntp_start(void)
{
    static bool done;
    if (done) return;
    done = true;

    esp_sntp_config_t cfg = ESP_NETIF_SNTP_DEFAULT_CONFIG_MULTIPLE(2,
        ESP_SNTP_SERVER_LIST("ntp.aliyun.com", "pool.ntp.org"));
    esp_err_t err = esp_netif_sntp_init(&cfg);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "sntp init failed: %s", esp_err_to_name(err));
    }
}

int64_t net_now(void)
{
    time_t now = time(NULL);
    if (now >= TS_SANITY_MIN) return (int64_t)now;

    int64_t cached = netcfg_get_last_ts();
    if (cached >= TS_SANITY_MIN) {
        // Rough catch-up: add the time spent asleep since it was latched
        return cached + (int64_t)(esp_timer_get_time() / 1000000LL);
    }
    return 0;
}

// ── Bring-up / tear-down ────────────────────────────────────────────────────

net_err_t net_bring_up(void)
{
    net_config_t ncfg;
    netcfg_load(&ncfg);
    if (!ncfg.present || ncfg.wifi_ssid[0] == '\0') {
        ESP_LOGW(TAG, "no wifi ssid provisioned");
        return NET_ERR_NO_CONFIG;
    }

    wifi_lazy_init();

    wifi_config_t wcfg = {
        .sta = {
            .scan_method = WIFI_FAST_SCAN,
            .sort_method = WIFI_CONNECT_AP_BY_SIGNAL,
        },
    };
    strlcpy((char *)wcfg.sta.ssid, ncfg.wifi_ssid, sizeof(wcfg.sta.ssid));
    strlcpy((char *)wcfg.sta.password, ncfg.wifi_pass, sizeof(wcfg.sta.password));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wcfg));
    ESP_ERROR_CHECK(esp_wifi_start());
    s_wifi_started = true;
    ESP_LOGI(TAG, "connecting to %s ...", ncfg.wifi_ssid);

    EventBits_t bits = xEventGroupWaitBits(s_events, EV_GOT_IP, pdFALSE, pdFALSE,
                                           pdMS_TO_TICKS(WIFI_CONNECT_TIMEOUT_MS));
    if (!(bits & EV_GOT_IP)) {
        ESP_LOGE(TAG, "no IP within %d ms", WIFI_CONNECT_TIMEOUT_MS);
        return NET_ERR_WIFI;
    }

    if (ncfg.portal_user[0] != '\0' && ncfg.portal_pass[0] != '\0') {
        portal_try_auth(ncfg.portal_user, ncfg.portal_pass);
    }

    sntp_start();
    if (esp_netif_sntp_sync_wait(pdMS_TO_TICKS(SNTP_SYNC_TIMEOUT_MS)) == ESP_OK) {
        netcfg_set_last_ts((int64_t)time(NULL));
        ESP_LOGI(TAG, "time synced: %lld", (long long)time(NULL));
    } else if (net_now() == 0) {
        ESP_LOGW(TAG, "no usable clock (SNTP timeout, no fallback)");
        return NET_ERR_TIME;
    } else {
        ESP_LOGW(TAG, "SNTP timeout, using fallback clock");
    }

    return NET_OK;
}

void net_tear_down(void)
{
    if (s_wifi_started) {
        esp_wifi_stop();
        s_wifi_started = false;
        xEventGroupClearBits(s_events, EV_GOT_IP);
        ESP_LOGI(TAG, "wifi stopped");
    }
}
