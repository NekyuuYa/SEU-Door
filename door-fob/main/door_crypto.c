#include "door_crypto.h"
#include <string.h>
#include <stdio.h>

static const uint8_t KEY_CONST[16] = {
    172, 171, 188, 218, 174, 191, 20, 38,
    53, 66, 84, 101, 114, 135, 146, 1
};

static const uint8_t CRC8_TABLE[256] = {
    0, 94, 188, 226, 97, 63, 221, 131, 194, 156, 126, 32, 163, 253, 31, 65,
    157, 195, 33, 127, 252, 162, 64, 30, 95, 1, 227, 189, 62, 96, 130, 220,
    35, 125, 159, 193, 66, 28, 254, 160, 225, 191, 93, 3, 128, 222, 60, 98,
    190, 224, 2, 92, 223, 129, 99, 61, 124, 34, 192, 158, 29, 67, 161, 255,
    70, 24, 250, 164, 39, 121, 155, 197, 132, 218, 56, 102, 229, 187, 89, 7,
    219, 133, 103, 57, 186, 228, 6, 88, 25, 71, 165, 251, 120, 38, 196, 154,
    101, 59, 217, 135, 4, 90, 184, 230, 167, 249, 27, 69, 198, 152, 122, 36,
    248, 166, 68, 26, 153, 199, 37, 123, 58, 100, 134, 216, 91, 5, 231, 185,
    140, 210, 48, 110, 237, 179, 81, 15, 78, 16, 242, 172, 47, 113, 147, 205,
    17, 79, 173, 243, 112, 46, 204, 146, 211, 141, 111, 49, 178, 236, 14, 80,
    175, 241, 19, 77, 206, 144, 114, 44, 109, 51, 209, 143, 12, 82, 176, 238,
    50, 108, 142, 208, 83, 13, 239, 177, 240, 174, 76, 18, 145, 207, 45, 115,
    202, 148, 118, 40, 171, 245, 23, 73, 8, 86, 180, 234, 105, 55, 213, 139,
    87, 9, 235, 181, 54, 104, 138, 212, 149, 203, 41, 119, 244, 170, 72, 22,
    233, 183, 85, 11, 136, 214, 52, 106, 43, 117, 151, 201, 74, 20, 246, 168,
    116, 42, 200, 150, 21, 75, 169, 247, 182, 232, 10, 84, 215, 137, 107, 53
};

static inline uint32_t u32(uint64_t val) {
    return (uint32_t)(val & 0xFFFFFFFF);
}

void derive_key(int32_t device_id, uint8_t out_key[16]) {
    // deviceId -> little-endian bytes -> reassemble as big-endian uint32
    uint8_t id_bytes[4];
    memcpy(id_bytes, &device_id, 4);  // little-endian on ESP32-C3

    uint32_t value = ((uint32_t)id_bytes[0] << 24) |
                     ((uint32_t)id_bytes[1] << 16) |
                     ((uint32_t)id_bytes[2] << 8)  |
                     (uint32_t)id_bytes[3];

    // KEY_CONST as 4 little-endian uint32
    uint32_t words[4];
    for (int i = 0; i < 4; i++) {
        memcpy(&words[i], &KEY_CONST[i * 4], 4);
    }

    for (int i = 0; i < 4; i++) {
        uint32_t delta = u32(2654435769UL + 305419896UL * i);
        uint32_t left   = u32((uint64_t)(value & delta) + i);
        uint32_t middle  = u32((uint64_t)(value | delta) - 2UL * i);
        uint32_t right   = u32((~value) ^ delta);
        uint32_t transformed = u32((uint64_t)left + middle - right);
        words[i] ^= transformed;
    }

    // Write as big-endian
    for (int i = 0; i < 4; i++) {
        out_key[i * 4 + 0] = (words[i] >> 24) & 0xFF;
        out_key[i * 4 + 1] = (words[i] >> 16) & 0xFF;
        out_key[i * 4 + 2] = (words[i] >> 8)  & 0xFF;
        out_key[i * 4 + 3] = (words[i])       & 0xFF;
    }
}

void rc4_encrypt(const uint8_t *data, size_t len, const uint8_t *key, uint8_t *out) {
    uint8_t sbox[256];
    for (int i = 0; i < 256; i++) sbox[i] = i;

    int j = 0;
    for (int i = 0; i < 256; i++) {
        j = (j + sbox[i] + key[i % 16]) & 0xFF;
        uint8_t tmp = sbox[i];
        sbox[i] = sbox[j];
        sbox[j] = tmp;
    }

    int x = 0, y = 0;
    for (size_t i = 0; i < len; i++) {
        x = (x + 1) & 0xFF;
        y = (y + sbox[x]) & 0xFF;
        uint8_t tmp = sbox[x];
        sbox[x] = sbox[y];
        sbox[y] = tmp;
        uint8_t key_byte = sbox[(sbox[x] + sbox[y]) & 0xFF];
        out[i] = data[i] ^ key_byte;
    }
}

uint8_t crc8(const uint8_t *data, size_t len) {
    uint8_t crc = 0;
    for (size_t i = 0; i < len; i++) {
        crc = CRC8_TABLE[crc ^ data[i]];
    }
    return crc;
}

void build_ble_command(uint8_t out[BLE_FRAME_SIZE], uint8_t cmd_type,
                       const uint8_t data[BLE_DATA_SIZE], int32_t device_id) {
    uint8_t key[16];
    derive_key(device_id, key);

    uint8_t plain[BLE_DATA_SIZE];
    memcpy(plain, data, BLE_DATA_SIZE);

    uint8_t encrypted[BLE_DATA_SIZE];
    rc4_encrypt(plain, BLE_DATA_SIZE, key, encrypted);

    memset(out, 0, BLE_FRAME_SIZE);
    out[0] = BLE_LENGTH_MARKER;
    out[1] = 0;
    out[2] = cmd_type;
    memcpy(&out[3], encrypted, BLE_DATA_SIZE);
    out[19] = crc8(plain, BLE_DATA_SIZE);
}

void build_header_payload(uint8_t out[BLE_DATA_SIZE], int32_t project_id,
                          const uint8_t credential[32]) {
    // raw = projectId(4 LE) + credential(32) = 36 bytes
    uint8_t raw[36];
    memcpy(raw, &project_id, 4);  // LE
    memcpy(&raw[4], credential, 32);

    memset(out, 0, BLE_DATA_SIZE);
    out[0] = BLE_FRAME_SIZE;  // BLE_HEADER_TOTAL_LENGTH = 20 (0x14)? check doc: 40
    // doc says: [0]=total_length(40), [1]=0, [2]=packet_count, [3]=crc8(raw)
    out[0] = 40;  // BLE_HEADER_TOTAL_LENGTH from Kotlin
    out[1] = 0;
    out[2] = (uint8_t)((40 + 14) / 15);  // packet count
    out[3] = crc8(raw, 36);
}

int build_credential_packets(uint8_t packets[3][BLE_DATA_SIZE], int32_t project_id,
                             const uint8_t credential[32], uint32_t ran) {
    // payload = ran(4 LE) + projectId(4 LE) + credential(32) = 40 bytes
    uint8_t payload[40];
    memcpy(payload, &ran, 4);
    memcpy(&payload[4], &project_id, 4);
    memcpy(&payload[8], credential, 32);

    int packet_count = (40 + 14) / 15;  // 3
    for (int i = 0; i < packet_count; i++) {
        memset(packets[i], 0, BLE_DATA_SIZE);
        packets[i][0] = (uint8_t)i;
        int start = i * 15;
        int copy_size = 15;
        if (start + copy_size > 40) copy_size = 40 - start;
        memcpy(&packets[i][1], &payload[start], copy_size);
    }
    return packet_count;
}

void build_open_payload(uint8_t out[BLE_DATA_SIZE]) {
    memset(out, 0, BLE_DATA_SIZE);
}

void build_refetch_payload(uint8_t out[BLE_DATA_SIZE], uint32_t credential_id) {
    // credential_id as 4 LE bytes (matches Kotlin littleEndianInt)
    memset(out, 0, BLE_DATA_SIZE);
    memcpy(out, &credential_id, 4);
}

void build_packet_read_payload(uint8_t out[BLE_DATA_SIZE], uint8_t packet_index) {
    memset(out, 0, BLE_DATA_SIZE);
    out[0] = packet_index;
}

ble_response_t parse_ble_response(const uint8_t frame[BLE_FRAME_SIZE], int32_t device_id) {
    ble_response_t resp = {0};

    uint8_t key[16];
    derive_key(device_id, key);

    resp.length_marker = frame[0];
    resp.command_type = frame[2];

    uint8_t encrypted[BLE_DATA_SIZE];
    memcpy(encrypted, &frame[3], BLE_DATA_SIZE);

    rc4_encrypt(encrypted, BLE_DATA_SIZE, key, resp.plain_data);

    uint8_t received_crc = frame[19];
    uint8_t calculated_crc = crc8(resp.plain_data, BLE_DATA_SIZE);
    resp.crc_valid = (received_crc == calculated_crc);

    resp.result_code = resp.plain_data[0];

    // Extract ran from response plain_data[4..7] (LE)
    memcpy(&resp.ran, &resp.plain_data[4], 4);

    return resp;
}

int parse_credential_hex(const char *hex, uint8_t out[32]) {
    if (strlen(hex) != CREDENTIAL_HEX_LEN) return -1;

    for (int i = 0; i < 32; i++) {
        uint8_t hi, lo;
        char ch = hex[i * 2];
        if      (ch >= '0' && ch <= '9') hi = ch - '0';
        else if (ch >= 'a' && ch <= 'f') hi = ch - 'a' + 10;
        else if (ch >= 'A' && ch <= 'F') hi = ch - 'A' + 10;
        else return -1;

        ch = hex[i * 2 + 1];
        if      (ch >= '0' && ch <= '9') lo = ch - '0';
        else if (ch >= 'a' && ch <= 'f') lo = ch - 'a' + 10;
        else if (ch >= 'A' && ch <= 'F') lo = ch - 'A' + 10;
        else return -1;

        out[i] = (hi << 4) | lo;
    }
    return 0;
}
