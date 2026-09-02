#pragma once

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#define BLE_FRAME_SIZE      20
#define BLE_DATA_SIZE       16
#define BLE_LENGTH_MARKER   0x14
#define BLE_CMD_HEADER      0x74
#define BLE_CMD_PACKET      0x75
#define BLE_CMD_REFETCH     0x76
#define BLE_CMD_PACKET_READ 0x77
#define BLE_CMD_OPEN        0x78

#define CREDENTIAL_HEX_LEN  64
#define CREDENTIAL_BYTE_LEN 32

typedef struct {
    uint8_t length_marker;
    uint8_t command_type;
    uint8_t plain_data[BLE_DATA_SIZE];
    bool    crc_valid;
    uint8_t result_code;
    uint32_t ran;
} ble_response_t;

// Core crypto
void     derive_key(int32_t device_id, uint8_t out_key[16]);
void     rc4_encrypt(const uint8_t *data, size_t len, const uint8_t *key, uint8_t *out);
uint8_t  crc8(const uint8_t *data, size_t len);

// BLE command builders
void build_ble_command(uint8_t out[BLE_FRAME_SIZE], uint8_t cmd_type,
                       const uint8_t data[BLE_DATA_SIZE], int32_t device_id);
void build_header_payload(uint8_t out[BLE_DATA_SIZE], int32_t project_id,
                          const uint8_t credential[32]);
int  build_credential_packets(uint8_t packets[3][BLE_DATA_SIZE], int32_t project_id,
                              const uint8_t credential[32], uint32_t ran);
void build_open_payload(uint8_t out[BLE_DATA_SIZE]);

// Credential refresh (rolling/chained key) command builders
void build_refetch_payload(uint8_t out[BLE_DATA_SIZE], uint32_t credential_id);
void build_packet_read_payload(uint8_t out[BLE_DATA_SIZE], uint8_t packet_index);

// BLE response parser
ble_response_t parse_ble_response(const uint8_t frame[BLE_FRAME_SIZE], int32_t device_id);

// Credential hex parse
int parse_credential_hex(const char *hex, uint8_t out[32]);
