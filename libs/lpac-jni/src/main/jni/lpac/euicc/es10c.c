#include "es10c.h"
#include "euicc.private.h"

#include "base64.h"
#include "derutil.h"
#include "hexutil.h"
#include "logger.h"
#include "rsp_limits.h"

#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int mark_singleton_seen(uint32_t *seen, uint32_t flag) {
    if ((*seen & flag) != 0) {
        return -1;
    }
    *seen |= flag;
    return 0;
}

int es10c_get_profiles_info(struct euicc_ctx *ctx, struct es10c_profile_info_list **profileInfoList) {
    int fret = 0;
    static const uint8_t profile_info_tags[] = {
        0x5A, 0x4F, 0x9F, 0x70, 0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0xB6, 0xB7, 0xB8, 0x99,
    };
    struct euicc_derutil_node n_tag_list = {
        .tag = 0x5C, // tagList
        .length = sizeof(profile_info_tags),
        .value = profile_info_tags,
    };
    struct euicc_derutil_node n_request = {
        .tag = 0xBF2D, // ProfileInfoListRequest
        .pack =
            {
                .child = &n_tag_list,
            },
    };
    uint32_t reqlen;
    uint8_t *respbuf = NULL;
    unsigned resplen;

    struct euicc_derutil_node tmpnode, n_profileInfoListOk, n_ProfileInfo;

    struct es10c_profile_info_list *list_wptr = NULL;
    struct es10c_profile_info_list *pending_profile = NULL;
    uint32_t profile_count = 0;

    long tmpint;

    if (ctx == NULL || profileInfoList == NULL)
        return -1;
    *profileInfoList = NULL;

    reqlen = sizeof(ctx->apdu._internal.request_buffer.body);
    if (euicc_derutil_pack(ctx->apdu._internal.request_buffer.body, &reqlen, &n_request)) {
        goto err;
    }

    if (es10x_command(ctx, &respbuf, &resplen, ctx->apdu._internal.request_buffer.body, reqlen) < 0) {
        goto err;
    }

    if (euicc_derutil_unpack_find_tag(&tmpnode, n_request.tag, respbuf, resplen) < 0 ||
        tmpnode.self.ptr != respbuf || tmpnode.self.length != resplen) {
        goto err;
    }

    if (euicc_derutil_unpack_find_tag(&n_profileInfoListOk, 0xA0, tmpnode.value, tmpnode.length) < 0 ||
        n_profileInfoListOk.self.ptr != tmpnode.value || n_profileInfoListOk.self.length != tmpnode.length) {
        goto err;
    }

    n_ProfileInfo.self.ptr = n_profileInfoListOk.value;
    n_ProfileInfo.self.length = 0;

    int profile_unpack_status;
    while ((profile_unpack_status = euicc_derutil_unpack_next(
                &n_ProfileInfo, &n_ProfileInfo, n_profileInfoListOk.value, n_profileInfoListOk.length)) == 0) {
        struct es10c_profile_info_list *p;
        uint32_t seen_tags = 0;
        int field_unpack_status;

        enum {
            SEEN_ICCID = 1U << 0,
            SEEN_ISDP_AID = 1U << 1,
            SEEN_PROFILE_STATE = 1U << 2,
            SEEN_NICKNAME = 1U << 3,
            SEEN_PROVIDER_NAME = 1U << 4,
            SEEN_PROFILE_NAME = 1U << 5,
            SEEN_ICON_TYPE = 1U << 6,
            SEEN_ICON = 1U << 7,
            SEEN_PROFILE_CLASS = 1U << 8,
            SEEN_NOTIFICATION_CONFIG = 1U << 9,
            SEEN_PROFILE_OWNER = 1U << 10,
            SEEN_DP_PROPRIETARY_DATA = 1U << 11,
            SEEN_POLICY_RULES = 1U << 12,
        };

        if (n_ProfileInfo.tag != 0xE3) {
            continue;
        }
        if (++profile_count > EUICC_RSP_PROFILE_COUNT) {
            goto err;
        }

        p = malloc(sizeof(struct es10c_profile_info_list));
        if (!p) {
            goto err;
        }

        memset(p, 0, sizeof(*p));
        pending_profile = p;

        tmpnode.self.ptr = n_ProfileInfo.value;
        tmpnode.self.length = 0;

        p->profileState = ES10C_PROFILE_STATE_NULL;
        p->profileClass = ES10C_PROFILE_CLASS_NULL;
        p->iconType = ES10C_ICON_TYPE_NULL;

        while ((field_unpack_status = euicc_derutil_unpack_next(
                    &tmpnode, &tmpnode, n_ProfileInfo.value, n_ProfileInfo.length)) == 0) {
            switch (tmpnode.tag) {
            case 0x5A:
                if (mark_singleton_seen(&seen_tags, SEEN_ICCID) < 0 ||
                    tmpnode.length != EUICC_RSP_ICCID_BYTES)
                    goto err;
                if (euicc_hexutil_bin2gsmbcd(p->iccid, sizeof(p->iccid), tmpnode.value, tmpnode.length) < 0)
                    goto err;
                break;
            case 0x4F:
                if (mark_singleton_seen(&seen_tags, SEEN_ISDP_AID) < 0 ||
                    tmpnode.length < EUICC_RSP_AID_MIN_BYTES || tmpnode.length > EUICC_RSP_AID_MAX_BYTES)
                    goto err;
                if (euicc_hexutil_bin2hex(p->isdpAid, sizeof(p->isdpAid), tmpnode.value, tmpnode.length) < 0)
                    goto err;
                break;
            case 0x9F70:
                if (mark_singleton_seen(&seen_tags, SEEN_PROFILE_STATE) < 0 ||
                    euicc_derutil_convert_bin2long(&tmpint, tmpnode.value, tmpnode.length) < 0)
                    goto err;
                switch (tmpint) {
                case ES10C_PROFILE_STATE_DISABLED:
                case ES10C_PROFILE_STATE_ENABLED:
                    p->profileState = tmpint;
                    break;
                default:
                    p->profileState = ES10C_PROFILE_STATE_UNDEFINED;
                    break;
                }
                break;
            case 0x90:
                if (mark_singleton_seen(&seen_tags, SEEN_NICKNAME) < 0 ||
                    euicc_derutil_validate_utf8(
                        tmpnode.value, tmpnode.length, EUICC_RSP_PROFILE_NICKNAME_CHARS) < 0)
                    goto err;
                p->profileNickname = malloc(tmpnode.length + 1);
                if (p->profileNickname == NULL)
                    goto err;
                memcpy(p->profileNickname, tmpnode.value, tmpnode.length);
                p->profileNickname[tmpnode.length] = '\0';
                break;
            case 0x91:
                if (mark_singleton_seen(&seen_tags, SEEN_PROVIDER_NAME) < 0 ||
                    euicc_derutil_validate_utf8(
                        tmpnode.value, tmpnode.length, EUICC_RSP_PROVIDER_NAME_CHARS) < 0)
                    goto err;
                p->serviceProviderName = malloc(tmpnode.length + 1);
                if (p->serviceProviderName == NULL)
                    goto err;
                memcpy(p->serviceProviderName, tmpnode.value, tmpnode.length);
                p->serviceProviderName[tmpnode.length] = '\0';
                break;
            case 0x92:
                if (mark_singleton_seen(&seen_tags, SEEN_PROFILE_NAME) < 0 ||
                    euicc_derutil_validate_utf8(
                        tmpnode.value, tmpnode.length, EUICC_RSP_PROFILE_NAME_CHARS) < 0)
                    goto err;
                p->profileName = malloc(tmpnode.length + 1);
                if (p->profileName == NULL)
                    goto err;
                memcpy(p->profileName, tmpnode.value, tmpnode.length);
                p->profileName[tmpnode.length] = '\0';
                break;
            case 0x93:
                if (mark_singleton_seen(&seen_tags, SEEN_ICON_TYPE) < 0 ||
                    euicc_derutil_convert_bin2long(&tmpint, tmpnode.value, tmpnode.length) < 0)
                    goto err;
                switch (tmpint) {
                case ES10C_ICON_TYPE_JPEG:
                case ES10C_ICON_TYPE_PNG:
                    p->iconType = tmpint;
                    break;
                default:
                    p->iconType = ES10C_ICON_TYPE_UNDEFINED;
                    break;
                }
                break;
            case 0x94:
                if (mark_singleton_seen(&seen_tags, SEEN_ICON) < 0 || tmpnode.length > EUICC_RSP_ICON_BYTES)
                    goto err;
                p->icon = malloc(euicc_base64_encode_len(tmpnode.length));
                if (p->icon == NULL)
                    goto err;
                euicc_base64_encode(p->icon, tmpnode.value, tmpnode.length);
                break;
            case 0x95:
                if (mark_singleton_seen(&seen_tags, SEEN_PROFILE_CLASS) < 0 ||
                    euicc_derutil_convert_bin2long(&tmpint, tmpnode.value, tmpnode.length) < 0)
                    goto err;
                switch (tmpint) {
                case ES10C_PROFILE_CLASS_TEST:
                case ES10C_PROFILE_CLASS_PROVISIONING:
                case ES10C_PROFILE_CLASS_OPERATIONAL:
                    p->profileClass = tmpint;
                    break;
                default:
                    p->profileClass = ES10C_PROFILE_CLASS_UNDEFINED;
                    break;
                }
                break;
            case 0xB6: {
                struct euicc_derutil_node configuration, field;
                int configuration_unpack_status;
                static const char *operation_desc[] = {
                    "notificationInstall",
                    "notificationLocalEnable",
                    "notificationLocalDisable",
                    "notificationLocalDelete",
                    "notificationRpmEnable",
                    "notificationRpmDisable",
                    "notificationRpmDelete",
                    "loadRpmPackageResult",
                    NULL,
                };
                if (mark_singleton_seen(&seen_tags, SEEN_NOTIFICATION_CONFIG) < 0)
                    goto err;
                configuration.self.ptr = tmpnode.value;
                configuration.self.length = 0;
                while ((configuration_unpack_status = euicc_derutil_unpack_next(
                            &configuration, &configuration, tmpnode.value, tmpnode.length)) == 0) {
                    uint32_t configuration_seen = 0;
                    int notification_field_unpack_status;
                    if (configuration.tag != 0x30) {
                        continue;
                    }
                    field.self.ptr = configuration.value;
                    field.self.length = 0;
                    while ((notification_field_unpack_status = euicc_derutil_unpack_next(
                                &field, &field, configuration.value, configuration.length)) == 0) {
                        if (field.tag == 0x80) {
                            if (mark_singleton_seen(&configuration_seen, 1U << 0) < 0 || field.length == 0 ||
                                field.length > EUICC_RSP_BIT_STRING_BYTES)
                                goto err;
                            if (p->notificationConfigurationInfo.profileManagementOperation == NULL &&
                                euicc_derutil_convert_bin2bits_str(
                                    &p->notificationConfigurationInfo.profileManagementOperation,
                                    field.value,
                                    field.length,
                                    operation_desc)) {
                                goto err;
                            }
                        } else if (field.tag == 0x81) {
                            if (mark_singleton_seen(&configuration_seen, 1U << 1) < 0 || field.length == 0 ||
                                field.length > EUICC_RSP_FQDN_BYTES ||
                                euicc_derutil_validate_utf8(field.value, field.length, EUICC_RSP_FQDN_BYTES) < 0)
                                goto err;
                            if (p->notificationConfigurationInfo.notificationAddress == NULL) {
                                p->notificationConfigurationInfo.notificationAddress = malloc(field.length + 1);
                                if (p->notificationConfigurationInfo.notificationAddress == NULL)
                                    goto err;
                                memcpy(p->notificationConfigurationInfo.notificationAddress, field.value, field.length);
                                p->notificationConfigurationInfo.notificationAddress[field.length] = '\0';
                            }
                        }
                    }
                    if (notification_field_unpack_status < 0)
                        goto err;
                }
                if (configuration_unpack_status < 0)
                    goto err;
                break;
            }
            case 0xB7: {
                struct euicc_derutil_node owner;
                uint32_t owner_seen = 0;
                int owner_unpack_status;
                if (mark_singleton_seen(&seen_tags, SEEN_PROFILE_OWNER) < 0)
                    goto err;
                owner.self.ptr = tmpnode.value;
                owner.self.length = 0;
                while ((owner_unpack_status = euicc_derutil_unpack_next(
                            &owner, &owner, tmpnode.value, tmpnode.length)) == 0) {
                    char **target = NULL;
                    switch (owner.tag) {
                    case 0x80:
                        target = &p->profileOwner.mccmnc;
                        break;
                    case 0x81:
                        target = &p->profileOwner.gid1;
                        break;
                    case 0x82:
                        target = &p->profileOwner.gid2;
                        break;
                    default:
                        break;
                    }
                    if (target != NULL) {
                        uint32_t owner_flag = 1U << (owner.tag - 0x80);
                        const uint32_t max_length = owner.tag == 0x80 ? 3U : EUICC_RSP_GID_BYTES;
                        if (mark_singleton_seen(&owner_seen, owner_flag) < 0 || owner.length > max_length ||
                            (owner.tag == 0x80 && owner.length != 3U))
                            goto err;
                    }
                    if (target != NULL && owner.length > 0) {
                        *target = malloc((owner.length * 2) + 1);
                        if (*target == NULL)
                            goto err;
                        if (euicc_hexutil_bin2hex(
                                *target, (owner.length * 2) + 1, owner.value, owner.length) < 0)
                            goto err;
                    }
                }
                if (owner_unpack_status < 0 || p->profileOwner.mccmnc == NULL)
                    goto err;
                break;
            }
            case 0xB8: {
                struct euicc_derutil_node proprietary;
                uint32_t proprietary_seen = 0;
                int proprietary_unpack_status;
                if (mark_singleton_seen(&seen_tags, SEEN_DP_PROPRIETARY_DATA) < 0)
                    goto err;
                if (tmpnode.length > EUICC_RSP_DP_PROPRIETARY_BYTES)
                    goto err;
                proprietary.self.ptr = tmpnode.value;
                proprietary.self.length = 0;
                while ((proprietary_unpack_status = euicc_derutil_unpack_next(
                            &proprietary, &proprietary, tmpnode.value, tmpnode.length)) == 0) {
                    if (proprietary.tag == 0x80) {
                        if (mark_singleton_seen(&proprietary_seen, 1U) < 0 ||
                            proprietary.length == 0 || proprietary.length > EUICC_RSP_DP_PROPRIETARY_BYTES)
                            goto err;
                        p->dpProprietaryData.dpOid = malloc((proprietary.length * 2) + 1);
                        if (p->dpProprietaryData.dpOid == NULL) {
                            goto err;
                        }
                        if (euicc_hexutil_bin2hex(
                                p->dpProprietaryData.dpOid,
                                (proprietary.length * 2) + 1,
                                proprietary.value,
                                proprietary.length) < 0)
                            goto err;
                    }
                }
                if (proprietary_unpack_status < 0 || (proprietary_seen & 1U) == 0)
                    goto err;
                break;
            }
            case 0x99: {
                static const char *policy_desc[] = {"pprUpdateControl", "ppr1", "ppr2", "ppr3", NULL};
                if (mark_singleton_seen(&seen_tags, SEEN_POLICY_RULES) < 0 ||
                    tmpnode.length > EUICC_RSP_BIT_STRING_BYTES)
                    goto err;
                if (euicc_derutil_convert_bin2bits_str(
                        &p->profilePolicyRules,
                        tmpnode.value,
                        tmpnode.length,
                        policy_desc)) {
                    goto err;
                }
                break;
            }
            }
        }
        if (field_unpack_status < 0)
            goto err;

        if (*profileInfoList == NULL) {
            *profileInfoList = p;
        } else {
            list_wptr->next = p;
        }

        list_wptr = p;
        pending_profile = NULL;
    }
    if (profile_unpack_status < 0)
        goto err;

    goto exit;

err:
    fret = -1;
    es10c_profile_info_list_free_all(pending_profile);
    es10c_profile_info_list_free_all(*profileInfoList);
    *profileInfoList = NULL;
exit:
    free(respbuf);
    respbuf = NULL;
    return fret;
}

static int es10c_enable_disable_delete_profile(struct euicc_ctx *ctx, uint16_t op_tag, const char *str_id,
                                               uint8_t refreshFlag) {
    int fret = 0;
    long response_code;
    uint8_t id[16];
    int id_len;
    size_t id_string_length;
    struct euicc_derutil_node n_request, n_choicer, n_profileIdentifierChoice, n_refreshFlag;
    uint32_t reqlen;
    uint8_t *respbuf = NULL;
    unsigned resplen;

    struct euicc_derutil_node tmpnode;

    if (ctx == NULL || str_id == NULL)
        return -1;
    id_string_length = strnlen(str_id, 33U);
    if (id_string_length > 32U)
        return -1;

    memset(&n_request, 0, sizeof(n_request));
    memset(&n_choicer, 0, sizeof(n_choicer));
    memset(&n_profileIdentifierChoice, 0, sizeof(n_profileIdentifierChoice));
    memset(&n_refreshFlag, 0, sizeof(n_refreshFlag));

    if (id_string_length == 32U) {
        if ((id_len = euicc_hexutil_hex2bin(id, sizeof(id), str_id)) < 0) {
            return -1;
        }
        n_profileIdentifierChoice.tag = 0x4F;
    } else {
        if (id_string_length < 10U || id_string_length > 20U)
            return -1;
        for (size_t i = 0; i < id_string_length; i++) {
            if (str_id[i] < '0' || str_id[i] > '9')
                return -1;
        }
        if ((id_len = euicc_hexutil_gsmbcd2bin(id, sizeof(id), str_id, 10)) < 0) {
            return -1;
        }
        id_len = EUICC_RSP_ICCID_BYTES;
        n_profileIdentifierChoice.tag = 0x5A;
    }
    n_profileIdentifierChoice.length = id_len;
    n_profileIdentifierChoice.value = id;

    if (refreshFlag & 0x80) {
        refreshFlag &= 0x7F;

        if (refreshFlag) {
            refreshFlag = 0xFF;
        }

        n_refreshFlag.tag = 0x81;
        n_refreshFlag.length = 1;
        n_refreshFlag.value = &refreshFlag;

        n_choicer.tag = 0xA0;
        n_choicer.pack.child = &n_profileIdentifierChoice;
        n_choicer.pack.next = &n_refreshFlag;

        n_request.pack.child = &n_choicer;
    } else {
        n_request.pack.child = &n_profileIdentifierChoice;
    }
    n_request.tag = op_tag;

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

int es10c_enable_profile(struct euicc_ctx *ctx, const char *id, uint8_t refreshFlag) {
    if (refreshFlag) {
        refreshFlag = 0xFF;
    } else {
        refreshFlag = 0x80;
    }
    return es10c_enable_disable_delete_profile(ctx, 0xBF31, id, refreshFlag);
}

int es10c_disable_profile(struct euicc_ctx *ctx, const char *id, uint8_t refreshFlag) {
    if (refreshFlag) {
        refreshFlag = 0xFF;
    } else {
        refreshFlag = 0x80;
    }
    return es10c_enable_disable_delete_profile(ctx, 0xBF32, id, refreshFlag);
}

int es10c_delete_profile(struct euicc_ctx *ctx, const char *id) {
    return es10c_enable_disable_delete_profile(ctx, 0xBF33, id, 0);
}

int es10c_euicc_memory_reset(struct euicc_ctx *ctx) {
    int fret = 0;
    long response_code;
    struct euicc_derutil_node n_request = {
        .tag = 0xBF34, // EuiccMemoryResetRequest
        .pack =
            {
                .child =
                    &(struct euicc_derutil_node){
                        .tag = 0x82, // resetOptions
                        .length = 2,
                        .value = (const uint8_t[]){0x05, 0xE0},
                    },
            },
    };
    uint32_t reqlen;
    uint8_t *respbuf = NULL;
    unsigned resplen;

    struct euicc_derutil_node tmpnode;

    if (ctx == NULL)
        return -1;

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

int es10c_get_eid(struct euicc_ctx *ctx, char **eidValue) {
    int fret = 0;
    struct euicc_derutil_node n_request = {
        .tag = 0xBF3E, // GetEuiccDataRequest
        .pack =
            {
                .child =
                    &(struct euicc_derutil_node){
                        .tag = 0x5C, // tagList
                        .length = 1,
                        .value = (const uint8_t[]){0x5A},
                    },
            },
    };
    uint32_t reqlen;
    uint8_t *respbuf = NULL;
    unsigned resplen;

    struct euicc_derutil_node tmpnode, n_response, eid_node;
    int eid_unpack_status;
    uint32_t eid_count = 0;

    if (ctx == NULL || eidValue == NULL)
        return -1;
    *eidValue = NULL;

    reqlen = sizeof(ctx->apdu._internal.request_buffer.body);
    if (euicc_derutil_pack(ctx->apdu._internal.request_buffer.body, &reqlen, &n_request)) {
        goto err;
    }

    if (es10x_command(ctx, &respbuf, &resplen, ctx->apdu._internal.request_buffer.body, reqlen) < 0) {
        goto err;
    }

    if (euicc_derutil_unpack_find_tag(&n_response, n_request.tag, respbuf, resplen) < 0 ||
        n_response.self.ptr != respbuf || n_response.self.length != resplen) {
        goto err;
    }

    eid_node.self.ptr = n_response.value;
    eid_node.self.length = 0;
    while ((eid_unpack_status = euicc_derutil_unpack_next(
                &eid_node, &eid_node, n_response.value, n_response.length)) == 0) {
        if (eid_node.tag != 0x5A)
            continue;
        if (++eid_count > 1)
            goto err;
        tmpnode = eid_node;
    }
    if (eid_unpack_status < 0 || eid_count != 1 || tmpnode.length != EUICC_RSP_EID_BYTES) {
        goto err;
    }

    *eidValue = malloc((tmpnode.length * 2) + 1);
    if (*eidValue == NULL) {
        goto err;
    }

    if (euicc_hexutil_bin2hex(*eidValue, (tmpnode.length * 2) + 1, tmpnode.value, tmpnode.length) < 0)
        goto err;

    goto exit;

err:
    fret = -1;
    free(*eidValue);
    *eidValue = NULL;
exit:
    free(respbuf);
    respbuf = NULL;
    return fret;
}

int es10c_set_nickname(struct euicc_ctx *ctx, const char *iccid, const char *profileNickname) {
    int fret = 0;
    long response_code;
    size_t iccid_len;
    size_t nickname_len;
    uint8_t asn1iccid[10];
    struct euicc_derutil_node n_request, n_iccid, n_profileNickname;
    uint32_t reqlen;
    uint8_t *respbuf = NULL;
    unsigned resplen;

    struct euicc_derutil_node tmpnode;

    memset(&n_request, 0, sizeof(n_request));
    memset(&n_iccid, 0, sizeof(n_iccid));
    memset(&n_profileNickname, 0, sizeof(n_profileNickname));

    if (ctx == NULL || iccid == NULL || profileNickname == NULL) {
        goto err;
    }
    iccid_len = strnlen(iccid, 21U);
    if (iccid_len < 10U || iccid_len > 20U)
        goto err;
    for (size_t i = 0; i < iccid_len; i++) {
        if (iccid[i] < '0' || iccid[i] > '9')
            goto err;
    }
    nickname_len = strnlen(profileNickname, (EUICC_RSP_PROFILE_NICKNAME_CHARS * 4U) + 1U);
    if (nickname_len > EUICC_RSP_PROFILE_NICKNAME_CHARS * 4U ||
        euicc_derutil_validate_utf8(
            (const uint8_t *)profileNickname, (uint32_t)nickname_len, EUICC_RSP_PROFILE_NICKNAME_CHARS) < 0 ||
        euicc_hexutil_gsmbcd2bin(asn1iccid, sizeof(asn1iccid), iccid, 10) < 0) {
        goto err;
    }

    n_request.tag = 0xBF29;
    n_request.pack.child = &n_iccid;

    n_iccid.tag = 0x5A;
    n_iccid.length = sizeof(asn1iccid);
    n_iccid.value = asn1iccid;
    n_iccid.pack.next = &n_profileNickname;

    n_profileNickname.tag = 0x90;
    n_profileNickname.length = (uint32_t)nickname_len;
    n_profileNickname.value = (const uint8_t *)profileNickname;

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

void es10c_profile_info_list_free_all(struct es10c_profile_info_list *profileInfoList) {
    while (profileInfoList) {
        struct es10c_profile_info_list *next = profileInfoList->next;
        free(profileInfoList->profileNickname);
        free(profileInfoList->serviceProviderName);
        free(profileInfoList->profileName);
        free(profileInfoList->icon);
        free(profileInfoList->notificationConfigurationInfo.profileManagementOperation);
        free(profileInfoList->notificationConfigurationInfo.notificationAddress);
        free(profileInfoList->profileOwner.mccmnc);
        free(profileInfoList->profileOwner.gid1);
        free(profileInfoList->profileOwner.gid2);
        free(profileInfoList->dpProprietaryData.dpOid);
        free(profileInfoList->profilePolicyRules);
        free(profileInfoList);
        profileInfoList = next;
    }
}
