idf_component_register(SRCS "main.c" "door_crypto.c" "door_ble.c" "cred_store.c" "config_service.c"
                            "net_config.c" "net_service.c" "door_api.c"
                       INCLUDE_DIRS "."
                       PRIV_REQUIRES bt nvs_flash esp_wifi esp_netif esp_http_client esp-tls mbedtls lwip)
