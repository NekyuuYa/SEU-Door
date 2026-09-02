#pragma once

#include "cred_store.h"
#include <stdbool.h>

// Register config GATT services (call once after NimBLE init)
void config_service_init(void);

// Start config mode advertising
void config_service_start(void);

// Start advertising (re-entry after disconnect)
void config_service_start_adv(void);

// Check if config service is active
bool config_service_active(void);

// Stop config service
void config_service_stop(void);
