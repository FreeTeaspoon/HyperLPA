#include "es10a.h"

#include "derutil.h"
#include "euicc.private.h"
#include "rsp_limits.h"

#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int es10a_get_euicc_configured_addresses(struct euicc_ctx *ctx, struct es10a_euicc_configured_addresses *address) {
    int fret = 0;
    struct euicc_derutil_node n_request = {
        .tag = 0xBF3C, // EuiccConfiguredAddressesRequest
    };
    uint32_t reqlen;
    uint8_t *respbuf = NULL;
    unsigned resplen;

    struct euicc_derutil_node tmpnode, n_Response;

    if (ctx == NULL || address == NULL)
        return -1;
    memset(address, 0, sizeof(*address));

    reqlen = sizeof(ctx->apdu._internal.request_buffer.body);
    if (euicc_derutil_pack(ctx->apdu._internal.request_buffer.body, &reqlen, &n_request)) {
        goto err;
    }

    if (es10x_command(ctx, &respbuf, &resplen, ctx->apdu._internal.request_buffer.body, reqlen) < 0) {
        goto err;
    }

    if (euicc_derutil_unpack_find_tag(&n_Response, n_request.tag, respbuf, resplen) < 0 ||
        n_Response.self.ptr != respbuf || n_Response.self.length != resplen) {
        goto err;
    }

    {
        uint32_t seen_addresses = 0;
        int address_unpack_status;
        tmpnode.self.ptr = n_Response.value;
        tmpnode.self.length = 0;
        while ((address_unpack_status = euicc_derutil_unpack_next(
                    &tmpnode, &tmpnode, n_Response.value, n_Response.length)) == 0) {
            char **target;
            uint32_t flag;

            if (tmpnode.tag == 0x80) {
                target = &address->defaultDpAddress;
                flag = 1U << 0;
            } else if (tmpnode.tag == 0x81) {
                target = &address->rootDsAddress;
                flag = 1U << 1;
            } else {
                continue;
            }

            if ((seen_addresses & flag) != 0 || tmpnode.length == 0 ||
                tmpnode.length > EUICC_RSP_FQDN_BYTES ||
                euicc_derutil_validate_utf8(tmpnode.value, tmpnode.length, EUICC_RSP_FQDN_BYTES) < 0)
                goto err;
            seen_addresses |= flag;

            *target = malloc(tmpnode.length + 1);
            if (*target == NULL)
                goto err;
            memcpy(*target, tmpnode.value, tmpnode.length);
            (*target)[tmpnode.length] = '\0';
        }
        if (address_unpack_status < 0)
            goto err;
    }

    goto exit;

err:
    fret = -1;
    free(address->defaultDpAddress);
    address->defaultDpAddress = NULL;
    free(address->rootDsAddress);
    address->rootDsAddress = NULL;
exit:
    free(respbuf);
    respbuf = NULL;
    return fret;
}

int es10a_set_default_dp_address(struct euicc_ctx *ctx, const char *smdp) {
    int fret = 0;
    long response_code;
    size_t smdp_len;
    struct euicc_derutil_node n_request = {
        .tag = 0xBF3F, // SetDefaultDpAddressRequest
        .pack =
            {
                .child =
                    &(struct euicc_derutil_node){
                        .tag = 0x80,
                        .length = 0,
                        .value = (const uint8_t *)smdp,
                    },
            },
    };
    uint32_t reqlen;
    uint8_t *respbuf = NULL;
    unsigned resplen;

    struct euicc_derutil_node tmpnode;

    if (ctx == NULL || smdp == NULL) {
        return -1;
    }
    smdp_len = strnlen(smdp, EUICC_RSP_FQDN_BYTES + 1U);
    if (smdp_len == 0 || smdp_len > EUICC_RSP_FQDN_BYTES ||
        euicc_derutil_validate_utf8((const uint8_t *)smdp, (uint32_t)smdp_len, EUICC_RSP_FQDN_BYTES) < 0) {
        return -1;
    }
    n_request.pack.child->length = (uint32_t)smdp_len;

    reqlen = sizeof(ctx->apdu._internal.request_buffer.body);
    if (euicc_derutil_pack(ctx->apdu._internal.request_buffer.body, &reqlen, &n_request)) {
        goto err;
    }

    if (es10x_command(ctx, &respbuf, &resplen, ctx->apdu._internal.request_buffer.body, reqlen) < 0) {
        goto err;
    }

    if (euicc_derutil_unpack_find_tag(&tmpnode, n_request.tag, respbuf, resplen) < 0) {
        goto err;
    }

    if (euicc_derutil_unpack_find_tag(&tmpnode, 0x80, tmpnode.value, tmpnode.length) < 0) {
        goto err;
    }

    if (euicc_derutil_convert_bin2long(&response_code, tmpnode.value, tmpnode.length) < 0 ||
        response_code > INT_MAX)
        goto err;
    fret = (int)response_code;

    goto exit;

err:
    fret = -1;
exit:
    free(respbuf);
    respbuf = NULL;
    return fret;
}

void es10a_euicc_configured_addresses_free(struct es10a_euicc_configured_addresses *address) {
    if (!address) {
        return;
    }
    free(address->defaultDpAddress);
    free(address->rootDsAddress);
    memset(address, 0x00, sizeof(struct es10a_euicc_configured_addresses));
}
