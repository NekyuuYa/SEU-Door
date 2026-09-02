#pragma once

#include "cred_store.h"

typedef enum {
    API_OK = 0,
    API_ERR_NO_SESSION,  // session fields not provisioned (need BLE export from app)
    API_ERR_TIME,        // no usable clock for the timestamp parameter
    API_ERR_HTTP,        // transport/TLS failure
    API_ERR_AUTH,        // server rejected the session -> re-export from app needed
    API_ERR_PARSE,       // response not understood
    API_ERR_NO_CRED,     // authenticated fine, but server returned no usable credential
} api_err_t;

// Refresh the door credential over the network using the provisioned session
// (see net_config.h). Tries /webapi/v1/staff/credentials first, then falls
// back to /webapi/v1/student/accommodation/details, then to the device-scoped
// /webapi/v1/staff/door_lock/credentials (using the bound lock device_id).
// Must be called with the network up (net_bring_up) from a task with
// TLS-sized stack.
api_err_t door_api_refresh_credential(stored_credential_t *out);

const char *door_api_err_str(api_err_t err);
