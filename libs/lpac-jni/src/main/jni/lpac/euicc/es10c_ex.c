#define _GNU_SOURCE
#include "es10c_ex.h"
#include "euicc.private.h"

#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "derutil.h"
#include "hexutil.h"
#include "rsp_limits.h"

static int _versiontype2str(char **out, const uint8_t *buffer, uint32_t buffer_len) {
    if (buffer_len != 3) {
        return -1;
    }
    return asprintf(out, "%d.%d.%d", buffer[0], buffer[1], buffer[2]);
}

static int mark_singleton_seen(uint32_t *seen, uint32_t flag) {
    if ((*seen & flag) != 0) {
        return -1;
    }
    *seen |= flag;
    return 0;
}

int es10c_ex_get_euiccinfo2(struct euicc_ctx *ctx, struct es10c_ex_euiccinfo2 *euiccinfo2) {
    int fret = 0;
    struct euicc_derutil_node n_request = {
        .tag = 0xBF22, // GetEuiccInfo2Request
    };
    uint32_t reqlen;
    uint8_t *respbuf = NULL;
    unsigned resplen;

    struct euicc_derutil_node tmpnode, tmpchidnode, n_EUICCInfo2;
    uint32_t seen_tags = 0;
    int top_unpack_status;

    enum {
        SEEN_PROFILE_VERSION = 1U << 0,
        SEEN_SVN = 1U << 1,
        SEEN_FIRMWARE_VERSION = 1U << 2,
        SEEN_CARD_RESOURCES = 1U << 3,
        SEEN_UICC_CAPABILITY = 1U << 4,
        SEEN_TS102241_VERSION = 1U << 5,
        SEEN_GLOBAL_PLATFORM_VERSION = 1U << 6,
        SEEN_RSP_CAPABILITY = 1U << 7,
        SEEN_CI_VERIFICATION_LIST = 1U << 8,
        SEEN_CI_SIGNING_LIST = 1U << 9,
        SEEN_EUICC_CATEGORY = 1U << 10,
        SEEN_FORBIDDEN_POLICY_RULES = 1U << 11,
        SEEN_PP_VERSION = 1U << 12,
        SEEN_SAS_ACCREDITATION = 1U << 13,
        SEEN_CERTIFICATION_DATA = 1U << 14,
    };

    if (ctx == NULL || euiccinfo2 == NULL)
        return -1;
    memset(euiccinfo2, 0, sizeof(struct es10c_ex_euiccinfo2));

    reqlen = sizeof(ctx->apdu._internal.request_buffer.body);
    if (euicc_derutil_pack(ctx->apdu._internal.request_buffer.body, &reqlen, &n_request) < 0) {
        goto err;
    }

    if (es10x_command(ctx, &respbuf, &resplen, ctx->apdu._internal.request_buffer.body, reqlen) < 0) {
        goto err;
    }

    if (euicc_derutil_unpack_find_tag(&n_EUICCInfo2, n_request.tag, respbuf, resplen) < 0 ||
        n_EUICCInfo2.self.ptr != respbuf || n_EUICCInfo2.self.length != resplen) {
        goto err;
    }

    tmpnode.self.ptr = n_EUICCInfo2.value;
    tmpnode.self.length = 0;
    while ((top_unpack_status = euicc_derutil_unpack_next(
                &tmpnode, &tmpnode, n_EUICCInfo2.value, n_EUICCInfo2.length)) == 0) {
        switch (tmpnode.tag) {
        case 0x81: // profileVersion
            if (mark_singleton_seen(&seen_tags, SEEN_PROFILE_VERSION) < 0 ||
                _versiontype2str(&euiccinfo2->profileVersion, tmpnode.value, tmpnode.length) < 0)
                goto err;
            break;
        case 0x82: // svn
            if (mark_singleton_seen(&seen_tags, SEEN_SVN) < 0 ||
                _versiontype2str(&euiccinfo2->svn, tmpnode.value, tmpnode.length) < 0)
                goto err;
            break;
        case 0x83: // euiccFirmwareVer
            if (mark_singleton_seen(&seen_tags, SEEN_FIRMWARE_VERSION) < 0 ||
                _versiontype2str(&euiccinfo2->euiccFirmwareVer, tmpnode.value, tmpnode.length) < 0)
                goto err;
            break;
        case 0x84: { // extCardResource
            uint32_t resource_seen = 0;
            long resource_value;
            int resource_unpack_status;
            if (mark_singleton_seen(&seen_tags, SEEN_CARD_RESOURCES) < 0 ||
                tmpnode.length > EUICC_RSP_CARD_RESOURCE_BYTES)
                goto err;
            tmpchidnode.self.ptr = tmpnode.value;
            tmpchidnode.self.length = 0;
            while ((resource_unpack_status = euicc_derutil_unpack_next(
                        &tmpchidnode, &tmpchidnode, tmpnode.value, tmpnode.length)) == 0) {
                switch (tmpchidnode.tag) {
                case 0x81:
                    if (mark_singleton_seen(&resource_seen, 1U << 0) < 0 ||
                        euicc_derutil_convert_bin2long(
                            &resource_value, tmpchidnode.value, tmpchidnode.length) < 0 ||
                        (unsigned long)resource_value > UINT32_MAX)
                        goto err;
                    euiccinfo2->extCardResource.installedApplication = (uint32_t)resource_value;
                    break;
                case 0x82:
                    if (mark_singleton_seen(&resource_seen, 1U << 1) < 0 ||
                        euicc_derutil_convert_bin2long(
                            &resource_value, tmpchidnode.value, tmpchidnode.length) < 0 ||
                        (unsigned long)resource_value > UINT32_MAX)
                        goto err;
                    euiccinfo2->extCardResource.freeNonVolatileMemory = (uint32_t)resource_value;
                    break;
                case 0x83:
                    if (mark_singleton_seen(&resource_seen, 1U << 2) < 0 ||
                        euicc_derutil_convert_bin2long(
                            &resource_value, tmpchidnode.value, tmpchidnode.length) < 0 ||
                        (unsigned long)resource_value > UINT32_MAX)
                        goto err;
                    euiccinfo2->extCardResource.freeVolatileMemory = (uint32_t)resource_value;
                    break;
                }
            }
            if (resource_unpack_status < 0)
                goto err;
            break;
        }
        case 0x85: { // uiccCapability
            static const char *desc[] = {"contactlessSupport",
                                         "usimSupport",
                                         "isimSupport",
                                         "csimSupport",
                                         "akaMilenage",
                                         "akaCave",
                                         "akaTuak128",
                                         "akaTuak256",
                                         "rfu1",
                                         "rfu2",
                                         "gbaAuthenUsim",
                                         "gbaAuthenISim",
                                         "mbmsAuthenUsim",
                                         "eapClient",
                                         "javacard",
                                         "multos",
                                         "multipleUsimSupport",
                                         "multipleIsimSupport",
                                         "multipleCsimSupport",
                                         "berTlvFileSupport",
                                         "dfLinkSupport",
                                         "catTp",
                                         "getIdentity",
                                         "profile-a-x25519",
                                         "profile-b-p256",
                                         "suciCalculatorApi",
                                         NULL};

            if (mark_singleton_seen(&seen_tags, SEEN_UICC_CAPABILITY) < 0 ||
                tmpnode.length > EUICC_RSP_BIT_STRING_BYTES ||
                euicc_derutil_convert_bin2bits_str(&euiccinfo2->uiccCapability, tmpnode.value, tmpnode.length, desc)) {
                goto err;
            }
        } break;
        case 0x86: // ts102241Version
            if (mark_singleton_seen(&seen_tags, SEEN_TS102241_VERSION) < 0 ||
                _versiontype2str(&euiccinfo2->ts102241Version, tmpnode.value, tmpnode.length) < 0)
                goto err;
            break;
        case 0x87: // globalplatformVersion
            if (mark_singleton_seen(&seen_tags, SEEN_GLOBAL_PLATFORM_VERSION) < 0 ||
                _versiontype2str(&euiccinfo2->globalplatformVersion, tmpnode.value, tmpnode.length) < 0)
                goto err;
            break;
        case 0x88: { // rspCapability
            static const char *desc[] = {"additionalProfile",
                                         "crlSupport",
                                         "rpmSupport",
                                         "testProfileSupport",
                                         "deviceInfoExtensibilitySupport",
                                         NULL};

            if (mark_singleton_seen(&seen_tags, SEEN_RSP_CAPABILITY) < 0 ||
                tmpnode.length > EUICC_RSP_BIT_STRING_BYTES ||
                euicc_derutil_convert_bin2bits_str(&euiccinfo2->rspCapability, tmpnode.value, tmpnode.length, desc)) {
                goto err;
            }
        } break;
        case 0xA9: { // euiccCiPKIdListForVerification
            uint32_t count;
            int key_unpack_status;

            if (mark_singleton_seen(&seen_tags, SEEN_CI_VERIFICATION_LIST) < 0)
                goto err;

            tmpchidnode.self.ptr = tmpnode.value;
            tmpchidnode.self.length = 0;
            count = 0;
            while ((key_unpack_status = euicc_derutil_unpack_next(
                        &tmpchidnode, &tmpchidnode, tmpnode.value, tmpnode.length)) == 0) {
                if (tmpchidnode.tag != 0x04 || tmpchidnode.length == 0 ||
                    tmpchidnode.length > EUICC_RSP_CI_PKID_BYTES || ++count > EUICC_RSP_CI_PKID_COUNT)
                    goto err;
            }
            if (key_unpack_status < 0)
                goto err;

            euiccinfo2->euiccCiPKIdListForVerification = malloc((count + 1) * sizeof(char *));
            if (!euiccinfo2->euiccCiPKIdListForVerification) {
                goto err;
            }
            memset(euiccinfo2->euiccCiPKIdListForVerification, 0, (count + 1) * sizeof(char *));

            tmpchidnode.self.ptr = tmpnode.value;
            tmpchidnode.self.length = 0;
            count = 0;
            while ((key_unpack_status = euicc_derutil_unpack_next(
                        &tmpchidnode, &tmpchidnode, tmpnode.value, tmpnode.length)) == 0) {
                euiccinfo2->euiccCiPKIdListForVerification[count] = malloc((tmpchidnode.length * 2 + 1) * sizeof(char));
                if (!euiccinfo2->euiccCiPKIdListForVerification[count]) {
                    goto err;
                }

                euicc_hexutil_bin2hex(euiccinfo2->euiccCiPKIdListForVerification[count], tmpchidnode.length * 2 + 1,
                                      tmpchidnode.value, tmpchidnode.length);
                count++;
            }
            if (key_unpack_status < 0)
                goto err;
        } break;
        case 0xAA: { // euiccCiPKIdListForSigning
            uint32_t count;
            int key_unpack_status;

            if (mark_singleton_seen(&seen_tags, SEEN_CI_SIGNING_LIST) < 0)
                goto err;

            tmpchidnode.self.ptr = tmpnode.value;
            tmpchidnode.self.length = 0;
            count = 0;
            while ((key_unpack_status = euicc_derutil_unpack_next(
                        &tmpchidnode, &tmpchidnode, tmpnode.value, tmpnode.length)) == 0) {
                if (tmpchidnode.tag != 0x04 || tmpchidnode.length == 0 ||
                    tmpchidnode.length > EUICC_RSP_CI_PKID_BYTES || ++count > EUICC_RSP_CI_PKID_COUNT)
                    goto err;
            }
            if (key_unpack_status < 0)
                goto err;

            euiccinfo2->euiccCiPKIdListForSigning = malloc((count + 1) * sizeof(char *));
            if (!euiccinfo2->euiccCiPKIdListForSigning) {
                goto err;
            }
            memset(euiccinfo2->euiccCiPKIdListForSigning, 0, (count + 1) * sizeof(char *));

            tmpchidnode.self.ptr = tmpnode.value;
            tmpchidnode.self.length = 0;
            count = 0;
            while ((key_unpack_status = euicc_derutil_unpack_next(
                        &tmpchidnode, &tmpchidnode, tmpnode.value, tmpnode.length)) == 0) {
                euiccinfo2->euiccCiPKIdListForSigning[count] = malloc((tmpchidnode.length * 2 + 1) * sizeof(char));
                if (!euiccinfo2->euiccCiPKIdListForSigning[count]) {
                    goto err;
                }

                euicc_hexutil_bin2hex(euiccinfo2->euiccCiPKIdListForSigning[count], tmpchidnode.length * 2 + 1,
                                      tmpchidnode.value, tmpchidnode.length);
                count++;
            }
            if (key_unpack_status < 0)
                goto err;
        } break;
        case 0xAB: { // euiccCategory
            long category;
            if (mark_singleton_seen(&seen_tags, SEEN_EUICC_CATEGORY) < 0 ||
                euicc_derutil_convert_bin2long(&category, tmpnode.value, tmpnode.length) < 0)
                goto err;
            switch (category) {
            case 1:
                euiccinfo2->euiccCategory = "basicEuicc";
                break;
            case 2:
                euiccinfo2->euiccCategory = "mediumEuicc";
                break;
            case 3:
                euiccinfo2->euiccCategory = "contactlessEuicc";
                break;
            case 0:
            default:
                euiccinfo2->euiccCategory = "other";
                break;
            }
        } break;
        case 0x99: { // forbiddenProfilePolicyRules
            static const char *desc[] = {"pprUpdateControl", "ppr1", "ppr2", "ppr3", NULL};

            if (mark_singleton_seen(&seen_tags, SEEN_FORBIDDEN_POLICY_RULES) < 0 ||
                tmpnode.length > EUICC_RSP_BIT_STRING_BYTES ||
                euicc_derutil_convert_bin2bits_str(&euiccinfo2->forbiddenProfilePolicyRules, tmpnode.value,
                                                   tmpnode.length, desc)) {
                goto err;
            }
        } break;
        case 0x04: // ppVersion
            if (mark_singleton_seen(&seen_tags, SEEN_PP_VERSION) < 0 ||
                _versiontype2str(&euiccinfo2->ppVersion, tmpnode.value, tmpnode.length) < 0)
                goto err;
            break;
        case 0x0C: // sasAcreditationNumber
            if (mark_singleton_seen(&seen_tags, SEEN_SAS_ACCREDITATION) < 0 ||
                euicc_derutil_validate_utf8(
                    tmpnode.value, tmpnode.length, EUICC_RSP_SAS_NUMBER_CHARS) < 0)
                goto err;
            euiccinfo2->sasAcreditationNumber = malloc(tmpnode.length + 1);
            if (!euiccinfo2->sasAcreditationNumber) {
                goto err;
            }
            memcpy(euiccinfo2->sasAcreditationNumber, tmpnode.value, tmpnode.length);
            euiccinfo2->sasAcreditationNumber[tmpnode.length] = 0;
            break;
        case 0xAC: { // certificationDataObject
            uint32_t certification_seen = 0;
            int certification_unpack_status;
            if (mark_singleton_seen(&seen_tags, SEEN_CERTIFICATION_DATA) < 0 ||
                tmpnode.length > EUICC_RSP_CERTIFICATION_DATA_BYTES)
                goto err;
            tmpchidnode.self.ptr = tmpnode.value;
            tmpchidnode.self.length = 0;
            while ((certification_unpack_status = euicc_derutil_unpack_next(
                        &tmpchidnode, &tmpchidnode, tmpnode.value, tmpnode.length)) == 0) {
                switch (tmpchidnode.tag) {
                case 0x80:
                    if (mark_singleton_seen(&certification_seen, 1U << 0) < 0 ||
                        euicc_derutil_validate_utf8(tmpchidnode.value, tmpchidnode.length,
                                                   EUICC_RSP_PLATFORM_LABEL_CHARS) < 0)
                        goto err;
                    euiccinfo2->certificationDataObject.platformLabel = malloc(tmpchidnode.length + 1);
                    if (!euiccinfo2->certificationDataObject.platformLabel) {
                        goto err;
                    }
                    memcpy(euiccinfo2->certificationDataObject.platformLabel, tmpchidnode.value, tmpchidnode.length);
                    euiccinfo2->certificationDataObject.platformLabel[tmpchidnode.length] = 0;
                    break;
                case 0x81:
                    if (mark_singleton_seen(&certification_seen, 1U << 1) < 0 ||
                        euicc_derutil_validate_utf8(tmpchidnode.value, tmpchidnode.length,
                                                   EUICC_RSP_DISCOVERY_URL_CHARS) < 0)
                        goto err;
                    euiccinfo2->certificationDataObject.discoveryBaseURL = malloc(tmpchidnode.length + 1);
                    if (!euiccinfo2->certificationDataObject.discoveryBaseURL) {
                        goto err;
                    }
                    memcpy(euiccinfo2->certificationDataObject.discoveryBaseURL, tmpchidnode.value, tmpchidnode.length);
                    euiccinfo2->certificationDataObject.discoveryBaseURL[tmpchidnode.length] = 0;
                    break;
                }
            }
            if (certification_unpack_status < 0)
                goto err;
            break;
        }
        }
    }
    if (top_unpack_status < 0)
        goto err;

    fret = 0;
    goto exit;

err:
    fret = -1;
    es10c_ex_euiccinfo2_free(euiccinfo2);
exit:
    free(respbuf);
    respbuf = NULL;
    return fret;
}

void es10c_ex_euiccinfo2_free(struct es10c_ex_euiccinfo2 *euiccinfo2) {
    if (!euiccinfo2) {
        return;
    }

    free(euiccinfo2->profileVersion);
    free(euiccinfo2->svn);
    free(euiccinfo2->euiccFirmwareVer);
    free(euiccinfo2->uiccCapability);
    free(euiccinfo2->ts102241Version);
    free(euiccinfo2->globalplatformVersion);
    free(euiccinfo2->rspCapability);
    if (euiccinfo2->euiccCiPKIdListForVerification) {
        for (int i = 0; euiccinfo2->euiccCiPKIdListForVerification[i] != NULL; i++) {
            free(euiccinfo2->euiccCiPKIdListForVerification[i]);
        }
        free(euiccinfo2->euiccCiPKIdListForVerification);
    }
    if (euiccinfo2->euiccCiPKIdListForSigning) {
        for (int i = 0; euiccinfo2->euiccCiPKIdListForSigning[i] != NULL; i++) {
            free(euiccinfo2->euiccCiPKIdListForSigning[i]);
        }
        free(euiccinfo2->euiccCiPKIdListForSigning);
    }
    free(euiccinfo2->forbiddenProfilePolicyRules);
    free(euiccinfo2->ppVersion);
    free(euiccinfo2->sasAcreditationNumber);
    free(euiccinfo2->certificationDataObject.discoveryBaseURL);
    free(euiccinfo2->certificationDataObject.platformLabel);

    memset(euiccinfo2, 0, sizeof(struct es10c_ex_euiccinfo2));
}
