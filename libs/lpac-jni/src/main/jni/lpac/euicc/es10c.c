#include "es10c.h"
#include "euicc.private.h"

#include "base64.h"
#include "derutil.h"
#include "hexutil.h"
#include "logger.h"

#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int es10c_get_profiles_info(struct euicc_ctx *ctx, struct es10c_profile_info_list **profileInfoList) {
    int fret = 0;
    static const uint8_t profile_info_tags[] = {
        0x5A, 0x4F, 0x9F, 0x70, 0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0xB6, 0xB7,
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

    int tmpint;

    *profileInfoList = NULL;

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

    if (euicc_derutil_unpack_find_tag(&n_profileInfoListOk, 0xA0, tmpnode.value, tmpnode.length) < 0) {
        goto err;
    }

    n_ProfileInfo.self.ptr = n_profileInfoListOk.value;
    n_ProfileInfo.self.length = 0;

    while (
        euicc_derutil_unpack_next(&n_ProfileInfo, &n_ProfileInfo, n_profileInfoListOk.value, n_profileInfoListOk.length)
        == 0) {
        struct es10c_profile_info_list *p;

        if (n_ProfileInfo.tag != 0xE3) {
            continue;
        }

        p = malloc(sizeof(struct es10c_profile_info_list));
        if (!p) {
            goto err;
        }

        memset(p, 0, sizeof(*p));

        tmpnode.self.ptr = n_ProfileInfo.value;
        tmpnode.self.length = 0;

        p->profileState = ES10C_PROFILE_STATE_NULL;
        p->profileClass = ES10C_PROFILE_CLASS_NULL;
        p->iconType = ES10C_ICON_TYPE_NULL;

        while (euicc_derutil_unpack_next(&tmpnode, &tmpnode, n_ProfileInfo.value, n_ProfileInfo.length) == 0) {
            switch (tmpnode.tag) {
            case 0x5A:
                euicc_hexutil_bin2gsmbcd(p->iccid, sizeof(p->iccid), tmpnode.value, tmpnode.length);
                break;
            case 0x4F:
                euicc_hexutil_bin2hex(p->isdpAid, sizeof(p->isdpAid), tmpnode.value, tmpnode.length);
                break;
            case 0x9F70:
                tmpint = euicc_derutil_convert_bin2long(tmpnode.value, tmpnode.length);
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
                p->profileNickname = malloc(tmpnode.length + 1);
                if (p->profileNickname) {
                    memcpy(p->profileNickname, tmpnode.value, tmpnode.length);
                    p->profileNickname[tmpnode.length] = '\0';
                }
                break;
            case 0x91:
                p->serviceProviderName = malloc(tmpnode.length + 1);
                if (p->serviceProviderName) {
                    memcpy(p->serviceProviderName, tmpnode.value, tmpnode.length);
                    p->serviceProviderName[tmpnode.length] = '\0';
                }
                break;
            case 0x92:
                p->profileName = malloc(tmpnode.length + 1);
                if (p->profileName) {
                    memcpy(p->profileName, tmpnode.value, tmpnode.length);
                    p->profileName[tmpnode.length] = '\0';
                }
                break;
            case 0x93:
                tmpint = euicc_derutil_convert_bin2long(tmpnode.value, tmpnode.length);
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
                p->icon = malloc(euicc_base64_encode_len(tmpnode.length));
                if (p->icon) {
                    euicc_base64_encode(p->icon, tmpnode.value, tmpnode.length);
                }
                break;
            case 0x95:
                tmpint = euicc_derutil_convert_bin2long(tmpnode.value, tmpnode.length);
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
                configuration.self.ptr = tmpnode.value;
                configuration.self.length = 0;
                while (euicc_derutil_unpack_next(&configuration, &configuration, tmpnode.value, tmpnode.length) == 0) {
                    if (configuration.tag != 0x30) {
                        continue;
                    }
                    field.self.ptr = configuration.value;
                    field.self.length = 0;
                    while (euicc_derutil_unpack_next(&field, &field, configuration.value, configuration.length) == 0) {
                        if (field.tag == 0x80 && field.length > 0 &&
                            p->notificationConfigurationInfo.profileManagementOperation == NULL) {
                            if (euicc_derutil_convert_bin2bits_str(
                                    &p->notificationConfigurationInfo.profileManagementOperation,
                                    field.value,
                                    field.length,
                                    operation_desc)) {
                                goto err;
                            }
                        } else if (field.tag == 0x81 && field.length > 0 &&
                                   p->notificationConfigurationInfo.notificationAddress == NULL) {
                            p->notificationConfigurationInfo.notificationAddress = malloc(field.length + 1);
                            if (p->notificationConfigurationInfo.notificationAddress != NULL) {
                                memcpy(p->notificationConfigurationInfo.notificationAddress, field.value, field.length);
                                p->notificationConfigurationInfo.notificationAddress[field.length] = '\0';
                            }
                        }
                    }
                    if (p->notificationConfigurationInfo.notificationAddress != NULL &&
                        p->notificationConfigurationInfo.profileManagementOperation != NULL) {
                        break;
                    }
                }
                break;
            }
            case 0xB7: {
                struct euicc_derutil_node owner;
                owner.self.ptr = tmpnode.value;
                owner.self.length = 0;
                while (euicc_derutil_unpack_next(&owner, &owner, tmpnode.value, tmpnode.length) == 0) {
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
                    if (target != NULL && owner.length > 0) {
                        *target = malloc((owner.length * 2) + 1);
                        if (*target != NULL) {
                            euicc_hexutil_bin2hex(*target, (owner.length * 2) + 1, owner.value, owner.length);
                        }
                    }
                }
                break;
            }
            case 0xB8: {
                struct euicc_derutil_node proprietary;
                proprietary.self.ptr = tmpnode.value;
                proprietary.self.length = 0;
                while (euicc_derutil_unpack_next(&proprietary, &proprietary, tmpnode.value, tmpnode.length) == 0) {
                    if (proprietary.tag == 0x80 && proprietary.length > 0) {
                        p->dpProprietaryData.dpOid = malloc((proprietary.length * 2) + 1);
                        if (p->dpProprietaryData.dpOid == NULL) {
                            goto err;
                        }
                        euicc_hexutil_bin2hex(
                            p->dpProprietaryData.dpOid,
                            (proprietary.length * 2) + 1,
                            proprietary.value,
                            proprietary.length);
                        break;
                    }
                }
                break;
            }
            case 0x99: {
                static const char *policy_desc[] = {"pprUpdateControl", "ppr1", "ppr2", "ppr3", NULL};
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

        if (*profileInfoList == NULL) {
            *profileInfoList = p;
        } else {
            list_wptr->next = p;
        }

        list_wptr = p;
    }

    goto exit;

err:
    fret = -1;
    es10c_profile_info_list_free_all(*profileInfoList);
exit:
    free(respbuf);
    respbuf = NULL;
    return fret;
}

static int es10c_enable_disable_delete_profile(struct euicc_ctx *ctx, uint16_t op_tag, const char *str_id,
                                               uint8_t refreshFlag) {
    int fret = 0;
    uint8_t id[16];
    int id_len;
    struct euicc_derutil_node n_request, n_choicer, n_profileIdentifierChoice, n_refreshFlag;
    uint32_t reqlen;
    uint8_t *respbuf = NULL;
    unsigned resplen;

    struct euicc_derutil_node tmpnode;

    memset(&n_request, 0, sizeof(n_request));
    memset(&n_choicer, 0, sizeof(n_choicer));
    memset(&n_profileIdentifierChoice, 0, sizeof(n_profileIdentifierChoice));
    memset(&n_refreshFlag, 0, sizeof(n_refreshFlag));

    if (strlen(str_id) == 32) {
        if ((id_len = euicc_hexutil_hex2bin(id, sizeof(id), str_id)) < 0) {
            return -1;
        }
        n_profileIdentifierChoice.tag = 0x4F;
    } else {
        if ((id_len = euicc_hexutil_gsmbcd2bin(id, sizeof(id), str_id, 10)) < 0) {
            return -1;
        }
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

    fret = euicc_derutil_convert_bin2long(tmpnode.value, tmpnode.length);

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

    fret = euicc_derutil_convert_bin2long(tmpnode.value, tmpnode.length);

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

    struct euicc_derutil_node tmpnode;

    reqlen = sizeof(ctx->apdu._internal.request_buffer.body);
    if (euicc_derutil_pack(ctx->apdu._internal.request_buffer.body, &reqlen, &n_request)) {
        goto err;
    }

    if (es10x_command(ctx, &respbuf, &resplen, ctx->apdu._internal.request_buffer.body, reqlen) < 0) {
        goto err;
    }

    if (euicc_derutil_unpack_find_tag(&tmpnode, n_request.tag, respbuf, resplen)) {
        goto err;
    }

    if (euicc_derutil_unpack_find_tag(&tmpnode, 0x5A, tmpnode.value, tmpnode.length)) {
        goto err;
    }

    *eidValue = malloc((tmpnode.length * 2) + 1);
    if (*eidValue == NULL) {
        goto err;
    }

    euicc_hexutil_bin2hex(*eidValue, (tmpnode.length * 2) + 1, tmpnode.value, tmpnode.length);

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
    uint8_t asn1iccid[10];
    struct euicc_derutil_node n_request, n_iccid, n_profileNickname;
    uint32_t reqlen;
    uint8_t *respbuf = NULL;
    unsigned resplen;

    struct euicc_derutil_node tmpnode;

    memset(&n_request, 0, sizeof(n_request));
    memset(&n_iccid, 0, sizeof(n_iccid));
    memset(&n_profileNickname, 0, sizeof(n_profileNickname));

    if (euicc_hexutil_gsmbcd2bin(asn1iccid, sizeof(asn1iccid), iccid, 10) < 0) {
        goto err;
    }

    n_request.tag = 0xBF29;
    n_request.pack.child = &n_iccid;

    n_iccid.tag = 0x5A;
    n_iccid.length = sizeof(asn1iccid);
    n_iccid.value = asn1iccid;
    n_iccid.pack.next = &n_profileNickname;

    n_profileNickname.tag = 0x90;
    n_profileNickname.length = strlen(profileNickname);
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

    fret = euicc_derutil_convert_bin2long(tmpnode.value, tmpnode.length);

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
