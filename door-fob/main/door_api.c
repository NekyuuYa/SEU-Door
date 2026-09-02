// Client for the reverse-engineered door-lock cloud API (see
// /home/Nekyuu/Workplace/door-opener/api-doc.md and DoorApi.kt).
// Implements the session-based credential refresh path:
//   GET {server}/webapi/v1/staff/credentials            (primary)
//   GET {server}/webapi/v1/student/accommodation/details (fallback)
// Requests are signed GETs: params sorted by key -> "k=v&..." -> append
// "&key=<secret>" -> MD5 uppercase. Responses are JSON envelopes whose data
// field is base64(JSON) with tolerant padding/alphabet.

#include "door_api.h"
#include "net_config.h"
#include "net_service.h"
#include "door_crypto.h"
#include "esp_log.h"
#include "esp_http_client.h"
#include "esp_crt_bundle.h"
#include "esp_random.h"
#include "cJSON.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <ctype.h>
#include <stdint.h>

#define TAG "door-api"

#define PROJECT_ID  21048
#define APP_ID      20104

#define NONCE_LEN       32
#define SIGN_SRC_MAX    1024
#define QUERY_MAX       1024
#define RESP_MAX        (32 * 1024)

#define ALIASES(...) ((const char *const[]){ __VA_ARGS__, NULL })

typedef enum { RESP_HTTP_FAIL = 0, RESP_AUTH_FAIL, RESP_OK } resp_result_t;

// ── Primitives ──────────────────────────────────────────────────────────────

// ── Minimal MD5 (RFC 1321), one-shot ──
// IDF 6.2 hides mbedtls internals behind TF-PSA crypto, so MD5 and base64 are
// implemented locally to stay independent of SDK crypto layout.

static uint32_t md5_rotl(uint32_t x, int c) { return (x << c) | (x >> (32 - c)); }

static void md5_one(const uint8_t *msg, size_t len, uint8_t out[16])
{
    static const uint32_t K[64] = {
        0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee,
        0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
        0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be,
        0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
        0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa,
        0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
        0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed,
        0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
        0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c,
        0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
        0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05,
        0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
        0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039,
        0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
        0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1,
        0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
    };
    static const uint8_t S[16] = {
        7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21,
    };

    size_t total = len + 1;
    while (total % 64 != 56) total++;

    uint8_t *m = malloc(total + 8);
    if (!m) { memset(out, 0, 16); return; }
    memcpy(m, msg, len);
    m[len] = 0x80;
    memset(m + len + 1, 0, total - len - 1);
    uint64_t bitlen = (uint64_t)len * 8;
    memcpy(m + total, &bitlen, 8);   // little-endian target

    uint32_t h0 = 0x67452301, h1 = 0xefcdab89, h2 = 0x98badcfe, h3 = 0x10325476;
    for (size_t off = 0; off < total; off += 64) {
        uint32_t M[16];
        memcpy(M, m + off, 64);
        uint32_t A = h0, B = h1, C = h2, D = h3;
        for (int i = 0; i < 64; i++) {
            uint32_t F;
            int g;
            if (i < 16)      { F = (B & C) | (~B & D);   g = i; }
            else if (i < 32) { F = (D & B) | (~D & C);   g = (5 * i + 1) % 16; }
            else if (i < 48) { F = B ^ C ^ D;            g = (3 * i + 5) % 16; }
            else             { F = C ^ (B | ~D);         g = (7 * i) % 16; }
            F += A + K[i] + M[g];
            A = D; D = C; C = B;
            B += md5_rotl(F, S[(i / 16) * 4 + i % 4]);
        }
        h0 += A; h1 += B; h2 += C; h3 += D;
    }
    memcpy(out, &h0, 4);
    memcpy(out + 4, &h1, 4);
    memcpy(out + 8, &h2, 4);
    memcpy(out + 12, &h3, 4);
    free(m);
}

static void md5_upper(const char *src, char out[33])
{
    unsigned char digest[16];
    md5_one((const uint8_t *)src, strlen(src), digest);
    for (int i = 0; i < 16; i++) sprintf(out + 2 * i, "%02X", digest[i]);
    out[32] = 0;
}

// ── Base64 decode, tolerant of URL-safe alphabet and missing padding ──

static int b64_val(char c)
{
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+' || c == '-') return 62;
    if (c == '/' || c == '_') return 63;
    return -1;
}

static char *b64_decode(const char *in)
{
    size_t n = strlen(in);
    uint8_t *out = malloc(n / 4 * 3 + 4);
    if (!out) return NULL;

    size_t o = 0;
    uint32_t acc = 0;
    int bits = 0;
    for (const char *p = in; *p; p++) {
        if (*p == '=') break;
        int v = b64_val(*p);
        if (v < 0) { free(out); return NULL; }
        acc = (acc << 6) | (uint32_t)v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out[o++] = (uint8_t)((acc >> bits) & 0xFF);
        }
    }
    out[o] = 0;
    return (char *)out;
}

static void random_nonce(char out[NONCE_LEN + 1])
{
    static const char chars[] =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    uint8_t buf[NONCE_LEN];
    esp_fill_random(buf, sizeof(buf));
    for (int i = 0; i < NONCE_LEN; i++) out[i] = chars[buf[i] % 62];
    out[NONCE_LEN] = 0;
}

static bool url_encode(const char *in, char *out, size_t outsz)
{
    static const char hex[] = "0123456789ABCDEF";
    size_t o = 0;
    for (const unsigned char *p = (const unsigned char *)in; *p; p++) {
        unsigned char c = *p;
        if (isalnum(c) || c == '-' || c == '.' || c == '_' || c == '~') {
            if (o + 1 >= outsz) return false;
            out[o++] = (char)c;
        } else {
            if (o + 3 >= outsz) return false;
            out[o++] = '%';
            out[o++] = hex[c >> 4];
            out[o++] = hex[c & 0xF];
        }
    }
    out[o] = 0;
    return true;
}

typedef struct {
    const char *key;
    char val[96];
} kv_t;

static int kv_cmp(const void *a, const void *b)
{
    return strcmp(((const kv_t *)a)->key, ((const kv_t *)b)->key);
}

// Append pid/appid/timestamp/noncestr, build the sorted signed query string.
// Returns false when no usable clock is available.
static bool build_query(kv_t *kvs, int *n, const char *secret, bool with_pid_appid,
                        char *out, size_t outsz)
{
    int64_t ts = net_now();
    if (ts <= 0) return false;

    char tsbuf[24];
    snprintf(tsbuf, sizeof(tsbuf), "%lld", (long long)ts);
    char nonce[NONCE_LEN + 1];
    random_nonce(nonce);

    if (with_pid_appid) {
        kvs[(*n)].key = "pid";
        snprintf(kvs[(*n)].val, sizeof(kvs[0].val), "%d", PROJECT_ID);
        (*n)++;
        kvs[(*n)].key = "appid";
        snprintf(kvs[(*n)].val, sizeof(kvs[0].val), "%d", APP_ID);
        (*n)++;
    }
    kvs[(*n)].key = "timestamp";
    strlcpy(kvs[(*n)].val, tsbuf, sizeof(kvs[0].val));
    (*n)++;
    kvs[(*n)].key = "noncestr";
    strlcpy(kvs[(*n)].val, nonce, sizeof(kvs[0].val));
    (*n)++;

    qsort(kvs, *n, sizeof(kv_t), kv_cmp);

    char src[SIGN_SRC_MAX];
    size_t o = 0;
    for (int i = 0; i < *n; i++) {
        int w = snprintf(src + o, sizeof(src) - o, "%s%s=%s",
                         i ? "&" : "", kvs[i].key, kvs[i].val);
        if (w < 0 || (size_t)w >= sizeof(src) - o) return false;
        o += w;
    }
    int w = snprintf(src + o, sizeof(src) - o, "&key=%s", secret);
    if (w < 0 || (size_t)w >= sizeof(src) - o) return false;

    char sign[33];
    md5_upper(src, sign);

    char enc[2 * sizeof(((kv_t *)0)->val) + 1];
    o = 0;
    for (int i = 0; i < *n; i++) {
        url_encode(kvs[i].val, enc, sizeof(enc));
        w = snprintf(out + o, outsz - o, "%s%s=%s", i ? "&" : "", kvs[i].key, enc);
        if (w < 0 || (size_t)w >= outsz - o) return false;
        o += w;
    }
    snprintf(out + o, outsz - o, "&sign=%s", sign);
    return true;
}

// ── Response handling ───────────────────────────────────────────────────────

// Loose success check mirroring DoorApi.kt: success/result present-and-false
// means failure; otherwise code/status/errno must be 0/1/200 if present.
static resp_result_t envelope_check(const cJSON *env, api_err_t *err)
{
    const char *const bool_keys[] = { "success", "result", NULL };
    for (int i = 0; bool_keys[i]; i++) {
        const cJSON *v = cJSON_GetObjectItemCaseSensitive(env, bool_keys[i]);
        if (!v) continue;
        if (cJSON_IsBool(v)) {
            if (cJSON_IsFalse(v) || cJSON_IsNull(v)) goto fail;
            return RESP_OK;
        }
        if (cJSON_IsString(v) && v->valuestring) {
            if (strcasecmp(v->valuestring, "true") != 0) goto fail;
            return RESP_OK;
        }
        if (cJSON_IsNumber(v)) {
            if (v->valuedouble == 0) goto fail;
            return RESP_OK;
        }
    }
    const char *const code_keys[] = { "code", "status", "errno", NULL };
    for (int i = 0; code_keys[i]; i++) {
        const cJSON *v = cJSON_GetObjectItemCaseSensitive(env, code_keys[i]);
        if (!v) continue;
        if (cJSON_IsNumber(v)) {
            if (v->valuedouble != 0 && v->valuedouble != 1 && v->valuedouble != 200) goto fail;
        } else if (cJSON_IsString(v) && v->valuestring) {
            if (strcmp(v->valuestring, "0") && strcmp(v->valuestring, "1") &&
                strcmp(v->valuestring, "200")) goto fail;
        }
    }
    return RESP_OK;

fail:
    *err = API_ERR_AUTH;
    return RESP_AUTH_FAIL;
}

// HTTPS GET + envelope parse. On RESP_OK returns the decoded data object
// (caller frees). Text without base64 framing (plain JSON object) is accepted.
static resp_result_t http_fetch_data(const char *url, cJSON **data_out, api_err_t *err)
{
    *data_out = NULL;
    char *resp = malloc(RESP_MAX);
    if (!resp) { *err = API_ERR_HTTP; return RESP_HTTP_FAIL; }

    esp_http_client_config_t cfg = {
        .url = url,
        .timeout_ms = 10000,
        .buffer_size = 2048,
        .crt_bundle_attach = esp_crt_bundle_attach,
    };
    esp_http_client_handle_t client = esp_http_client_init(&cfg);
    if (!client) { free(resp); *err = API_ERR_HTTP; return RESP_HTTP_FAIL; }

    esp_err_t herr = esp_http_client_open(client, 0);
    if (herr != ESP_OK) {
        ESP_LOGW(TAG, "GET failed (%s): %s", url, esp_err_to_name(herr));
        esp_http_client_cleanup(client);
        free(resp);
        *err = API_ERR_HTTP;
        return RESP_HTTP_FAIL;
    }
    esp_http_client_fetch_headers(client);
    int n = esp_http_client_read_response(client, resp, RESP_MAX - 1);
    int status = esp_http_client_get_status_code(client);
    esp_http_client_close(client);
    esp_http_client_cleanup(client);

    if (n < 0) n = 0;
    resp[n] = 0;
    ESP_LOGI(TAG, "GET %s -> %d, %d bytes", url, status, n);

    if (status >= 400) {
        free(resp);
        *err = (status == 401 || status == 403) ? API_ERR_AUTH : API_ERR_HTTP;
        return status == 401 || status == 403 ? RESP_AUTH_FAIL : RESP_HTTP_FAIL;
    }

    cJSON *env = cJSON_Parse(resp);
    free(resp);
    if (!env) { *err = API_ERR_PARSE; return RESP_AUTH_FAIL; }

    api_err_t e = API_OK;
    resp_result_t r = envelope_check(env, &e);
    if (r != RESP_OK) {
        const char *const msg_keys[] = { "err_msg", "msg", "message", NULL };
        for (int i = 0; msg_keys[i]; i++) {
            const cJSON *m = cJSON_GetObjectItemCaseSensitive(env, msg_keys[i]);
            if (cJSON_IsString(m) && m->valuestring[0]) {
                ESP_LOGW(TAG, "server msg: %s", m->valuestring);
                break;
            }
        }
        cJSON_Delete(env);
        *err = e;
        return r;
    }

    const cJSON *data = cJSON_GetObjectItemCaseSensitive(env, "data");
    cJSON *out = NULL;
    if (cJSON_IsObject(data) || cJSON_IsArray(data)) {
        out = cJSON_Duplicate(data, true);
    } else if (cJSON_IsString(data) && data->valuestring) {
        const char *s = data->valuestring;
        while (isspace((unsigned char)*s)) s++;
        if (s[0] == '{' || s[0] == '[') {
            out = cJSON_Parse(s);
        } else {
            char *json = b64_decode(s);
            if (json) {
                out = cJSON_Parse(json);
                free(json);
            }
        }
    }
    cJSON_Delete(env);
    if (!out) { *err = API_ERR_PARSE; return RESP_AUTH_FAIL; }
    *data_out = out;
    *err = API_OK;
    return RESP_OK;
}

// Depth-first search for the first non-null value matching any alias
static const cJSON *find_field(const cJSON *node, const char *const *aliases)
{
    if (!node) return NULL;
    if (cJSON_IsObject(node)) {
        for (int i = 0; aliases[i]; i++) {
            const cJSON *v = cJSON_GetObjectItemCaseSensitive(node, aliases[i]);
            if (v && !cJSON_IsNull(v)) return v;
        }
        const cJSON *child;
        cJSON_ArrayForEach(child, node) {
            const cJSON *r = find_field(child, aliases);
            if (r) return r;
        }
    } else if (cJSON_IsArray(node)) {
        const cJSON *child;
        cJSON_ArrayForEach(child, node) {
            const cJSON *r = find_field(child, aliases);
            if (r) return r;
        }
    }
    return NULL;
}

static bool field_str(const cJSON *root, const char *const *aliases,
                      char *out, size_t sz)
{
    const cJSON *v = find_field(root, aliases);
    if (!v || !cJSON_IsString(v) || !v->valuestring) return false;
    strlcpy(out, v->valuestring, sz);
    return true;
}

static bool field_int(const cJSON *root, const char *const *aliases, long long *out)
{
    const cJSON *v = find_field(root, aliases);
    if (!v) return false;
    if (cJSON_IsNumber(v)) { *out = (long long)v->valuedouble; return true; }
    if (cJSON_IsString(v) && v->valuestring && isdigit((unsigned char)v->valuestring[0])) {
        *out = atoll(v->valuestring);
        return true;
    }
    return false;
}

// ── Door field aliases (same compatibility set as the app) ──────────────────

typedef struct {
    char device_id[24];
    char credential_id[24];
    char ble_mac[18];
    char credential[65];
    bool has_credential;
} lock_fields_t;

static void extract_lock_fields(const cJSON *root, bool prefer_door_lock, lock_fields_t *f)
{
    const cJSON *scope = root;
    if (prefer_door_lock) {
        const cJSON *dl = cJSON_GetObjectItemCaseSensitive(root, "door_lock");
        if (cJSON_IsObject(dl)) scope = dl;
    }
    long long v;
    if (field_int(scope, ALIASES("device_id", "deviceId"), &v))
        snprintf(f->device_id, sizeof(f->device_id), "%lld", v);
    if (field_int(scope, ALIASES("credential_id", "credentialId", "id"), &v))
        snprintf(f->credential_id, sizeof(f->credential_id), "%lld", v);
    field_str(scope, ALIASES("ble_mac", "bleMac"), f->ble_mac, sizeof(f->ble_mac));
    f->has_credential = field_str(scope,
        ALIASES("credential", "chain_key", "chainKey"), f->credential, sizeof(f->credential));
}

static api_err_t fetch_and_extract(const char *path, kv_t *kvs, int n, bool prefer_door_lock,
                                   lock_fields_t *fields)
{
    char query[QUERY_MAX];
    net_config_t ncfg;
    netcfg_load(&ncfg);

    if (!build_query(kvs, &n, ncfg.session_secret, true, query, sizeof(query)))
        return API_ERR_TIME;

    char url[QUERY_MAX + 192];
    snprintf(url, sizeof(url), "%s%s?%s", ncfg.server_url, path, query);

    cJSON *data = NULL;
    api_err_t err = API_OK;
    resp_result_t r = http_fetch_data(url, &data, &err);
    if (r != RESP_OK) return err;

    memset(fields, 0, sizeof(*fields));
    extract_lock_fields(data, prefer_door_lock, fields);
    cJSON_Delete(data);
    return API_OK;
}

api_err_t door_api_refresh_credential(stored_credential_t *out)
{
    net_config_t ncfg;
    netcfg_load(&ncfg);
    if (ncfg.server_url[0] == 0 || ncfg.session_secret[0] == 0 ||
        ncfg.user_id[0] == 0 || ncfg.identity_code[0] == 0) {
        return API_ERR_NO_SESSION;
    }
    // defensive: no trailing slash in joins
    size_t l = strlen(ncfg.server_url);
    if (ncfg.server_url[l - 1] == '/') ncfg.server_url[l - 1] = 0;

    // Bound lock device_id for the device-scoped endpoint: prefer the value
    // the caller already holds (out), else the one persisted in NVS.
    int32_t dev_id = out->device_id;
    if (dev_id == 0) {
        stored_credential_t cur;
        if (cred_load(&cur)) dev_id = cur.device_id;
    }

    int n;
    kv_t kvs[8];
    lock_fields_t f1 = {0}, f2 = {0}, f3 = {0};
    api_err_t e;

    // 1) /webapi/v1/staff/credentials?user_id&identitycode — same wire format as
    //    the app (DoorApi.kt fetchStaffCredentials): NO device_id param; the
    //    first record carries the server-issued chain key and its device_id.
    n = 0;
    kvs[n].key = "user_id"; strlcpy(kvs[n].val, ncfg.user_id, sizeof(kvs[0].val)); n++;
    kvs[n].key = "identitycode"; strlcpy(kvs[n].val, ncfg.identity_code, sizeof(kvs[0].val)); n++;
    e = fetch_and_extract("/webapi/v1/staff/credentials", kvs, n, false, &f1);
    ESP_LOGI(TAG, "staff/credentials -> %s", door_api_err_str(e));
    if (e == API_ERR_HTTP || e == API_ERR_AUTH) return e;

    // 2) fallback: /webapi/v1/student/accommodation/details?user_id&identitycode
    //    (older deployments embed door_lock directly in the response)
    if (!f1.has_credential) {
        n = 0;
        kvs[n].key = "user_id"; strlcpy(kvs[n].val, ncfg.user_id, sizeof(kvs[0].val)); n++;
        kvs[n].key = "identitycode"; strlcpy(kvs[n].val, ncfg.identity_code, sizeof(kvs[0].val)); n++;
        e = fetch_and_extract("/webapi/v1/student/accommodation/details", kvs, n, true, &f2);
        ESP_LOGI(TAG, "accommodation/details -> %s", door_api_err_str(e));
        if (e != API_OK) return e;
    }

    // 3) device-scoped fallback (matches the app's refresh path):
    //    /webapi/v1/staff/door_lock/credentials?device_id&user_id&identitycode
    if (!f1.has_credential && !f2.has_credential && dev_id > 0) {
        n = 0;
        kvs[n].key = "device_id";
        snprintf(kvs[n].val, sizeof(kvs[0].val), "%" PRId32, dev_id);
        n++;
        kvs[n].key = "user_id"; strlcpy(kvs[n].val, ncfg.user_id, sizeof(kvs[0].val)); n++;
        kvs[n].key = "identitycode"; strlcpy(kvs[n].val, ncfg.identity_code, sizeof(kvs[0].val)); n++;
        e = fetch_and_extract("/webapi/v1/staff/door_lock/credentials", kvs, n, false, &f3);
        ESP_LOGI(TAG, "door_lock/credentials -> %s", door_api_err_str(e));
        if (e != API_OK) return e;
    }

    lock_fields_t f = {0};
    if (f1.has_credential) f = f1;          // primary wins on conflicts
    if (f.device_id[0] == 0) strlcpy(f.device_id, f2.device_id, sizeof(f.device_id));
    if (f.credential_id[0] == 0) strlcpy(f.credential_id, f2.credential_id, sizeof(f.credential_id));
    if (f.ble_mac[0] == 0) strlcpy(f.ble_mac, f2.ble_mac, sizeof(f.ble_mac));
    if (!f.has_credential) { f.has_credential = f2.has_credential; strlcpy(f.credential, f2.credential, sizeof(f.credential)); }
    if (f.device_id[0] == 0) strlcpy(f.device_id, f3.device_id, sizeof(f.device_id));
    if (f.credential_id[0] == 0) strlcpy(f.credential_id, f3.credential_id, sizeof(f.credential_id));
    if (f.ble_mac[0] == 0) strlcpy(f.ble_mac, f3.ble_mac, sizeof(f.ble_mac));
    if (!f.has_credential) { f.has_credential = f3.has_credential; strlcpy(f.credential, f3.credential, sizeof(f.credential)); }

    if (f.device_id[0] == 0 || !f.has_credential) {
        ESP_LOGW(TAG, "no device_id/credential in response (dev=%s cred=%d)",
                 f.device_id, f.has_credential);
        return API_ERR_NO_CRED;
    }

    // credential must be 64 hex chars
    size_t clen = strlen(f.credential);
    if (clen != 64) {
        ESP_LOGW(TAG, "credential length %d != 64", (int)clen);
        return API_ERR_NO_CRED;
    }
    char upper[65];
    for (int i = 0; i < 64; i++) upper[i] = (char)toupper((unsigned char)f.credential[i]);
    upper[64] = 0;

    memset(out, 0, sizeof(*out));
    out->device_id = (int32_t)atoll(f.device_id);
    out->project_id = PROJECT_ID;
    if (parse_credential_hex(upper, out->credential) != 0) return API_ERR_NO_CRED;
    strlcpy(out->ble_mac, f.ble_mac, sizeof(out->ble_mac));
    out->credential_id = (uint32_t)strtoull(f.credential_id, NULL, 10);
    out->valid = true;
    return API_OK;
}

const char *door_api_err_str(api_err_t err)
{
    switch (err) {
    case API_OK:             return "ok";
    case API_ERR_NO_SESSION: return "no session (need BLE export from app)";
    case API_ERR_TIME:       return "no usable clock";
    case API_ERR_HTTP:       return "http failure";
    case API_ERR_AUTH:       return "auth rejected (re-export needed)";
    case API_ERR_PARSE:      return "unparseable response";
    case API_ERR_NO_CRED:    return "no credential in response";
    default:                 return "?";
    }
}
