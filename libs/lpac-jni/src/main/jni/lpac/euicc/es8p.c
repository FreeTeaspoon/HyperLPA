#include "es8p.h"

#include "base64.h"
#include "derutil.h"
#include "hexutil.h"
#include "logger.h"
#include "rsp_limits.h"

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

int es8p_metadata_parse(struct es8p_metadata **stru_metadata, const char *b64_Metadata) {
    int ret;
    uint8_t *metadata = NULL;
    int metadata_len = 0;
    struct euicc_derutil_node n_metadata, n_iter;
    struct es8p_metadata *p = NULL;
    uint32_t seen_tags = 0;
    int metadata_unpack_status;

    enum {
        SEEN_ICCID = 1U << 0,
        SEEN_PROVIDER_NAME = 1U << 1,
        SEEN_PROFILE_NAME = 1U << 2,
        SEEN_ICON_TYPE = 1U << 3,
        SEEN_ICON = 1U << 4,
        SEEN_PROFILE_CLASS = 1U << 5,
        SEEN_PROFILE_OWNER = 1U << 6,
        SEEN_NOTIFICATION_CONFIG = 1U << 7,
        SEEN_POLICY_RULES = 1U << 8,
    };

    if (stru_metadata == NULL) {
        return -1;
    }
    *stru_metadata = NULL;
    if (euicc_base64_validate(b64_Metadata, EUICC_RSP_METADATA_BASE64_BYTES) < 0) {
        return -1;
    }

    memset(&n_metadata, 0x00, sizeof(n_metadata));
    memset(&n_iter, 0x00, sizeof(n_iter));

    metadata = malloc(euicc_base64_decode_len(b64_Metadata));
    if (!metadata) {
        goto err;
    }

    if ((metadata_len = euicc_base64_decode(metadata, b64_Metadata)) < 0) {
        goto err;
    }

    if (euicc_derutil_unpack_find_tag(&n_metadata, 0xBF25, metadata, metadata_len) < 0) {
        goto err;
    }
    if (n_metadata.self.ptr != metadata || n_metadata.self.length != (uint32_t)metadata_len) {
        goto err;
    }

    if (!(p = malloc(sizeof(struct es8p_metadata)))) {
        goto err;
    }

    memset(p, 0, sizeof(*p));

    n_iter.self.ptr = n_metadata.value;
    n_iter.self.length = 0;

    p->profileClass = ES10C_PROFILE_CLASS_NULL;
    p->iconType = ES10C_ICON_TYPE_NULL;

    while ((metadata_unpack_status = euicc_derutil_unpack_next(
                &n_iter, &n_iter, n_metadata.value, n_metadata.length)) == 0) {
        long tmplong;
        switch (n_iter.tag) {
        case 0x5A:
            if (mark_singleton_seen(&seen_tags, SEEN_ICCID) < 0 ||
                n_iter.length != EUICC_RSP_ICCID_BYTES ||
                euicc_hexutil_bin2gsmbcd(p->iccid, sizeof(p->iccid), n_iter.value, n_iter.length) < 0)
                goto err;
            break;
        case 0x91:
            if (mark_singleton_seen(&seen_tags, SEEN_PROVIDER_NAME) < 0 ||
                euicc_derutil_validate_utf8(
                    n_iter.value, n_iter.length, EUICC_RSP_PROVIDER_NAME_CHARS) < 0)
                goto err;
            p->serviceProviderName = malloc(n_iter.length + 1);
            if (!p->serviceProviderName)
                goto err;
            memcpy(p->serviceProviderName, n_iter.value, n_iter.length);
            p->serviceProviderName[n_iter.length] = '\0';
            break;
        case 0x92:
            if (mark_singleton_seen(&seen_tags, SEEN_PROFILE_NAME) < 0 ||
                euicc_derutil_validate_utf8(
                    n_iter.value, n_iter.length, EUICC_RSP_PROFILE_NAME_CHARS) < 0)
                goto err;
            p->profileName = malloc(n_iter.length + 1);
            if (!p->profileName)
                goto err;
            memcpy(p->profileName, n_iter.value, n_iter.length);
            p->profileName[n_iter.length] = '\0';
            break;
        case 0x93:
            if (mark_singleton_seen(&seen_tags, SEEN_ICON_TYPE) < 0 ||
                euicc_derutil_convert_bin2long(&tmplong, n_iter.value, n_iter.length) < 0)
                goto err;
            switch (tmplong) {
            case ES10C_ICON_TYPE_JPEG:
            case ES10C_ICON_TYPE_PNG:
                p->iconType = tmplong;
                break;
            default:
                p->iconType = ES10C_ICON_TYPE_UNDEFINED;
                break;
            }
            break;
        case 0x94:
            if (mark_singleton_seen(&seen_tags, SEEN_ICON) < 0 || n_iter.length > EUICC_RSP_ICON_BYTES)
                goto err;
            p->icon = malloc(euicc_base64_encode_len(n_iter.length));
            if (!p->icon || euicc_base64_encode(p->icon, n_iter.value, n_iter.length) < 0)
                goto err;
            break;
        case 0x95:
            if (mark_singleton_seen(&seen_tags, SEEN_PROFILE_CLASS) < 0 ||
                euicc_derutil_convert_bin2long(&tmplong, n_iter.value, n_iter.length) < 0)
                goto err;
            switch (tmplong) {
            case ES10C_PROFILE_CLASS_TEST:
            case ES10C_PROFILE_CLASS_PROVISIONING:
            case ES10C_PROFILE_CLASS_OPERATIONAL:
                p->profileClass = tmplong;
                break;
            default:
                p->profileClass = ES10C_PROFILE_CLASS_UNDEFINED;
                break;
            }
            break;
        case 0xB7: {
            struct euicc_derutil_node owner;
            uint32_t owner_seen = 0;
            int owner_unpack_status;
            if (mark_singleton_seen(&seen_tags, SEEN_PROFILE_OWNER) < 0)
                goto err;
            owner.self.ptr = n_iter.value;
            owner.self.length = 0;
            while ((owner_unpack_status = euicc_derutil_unpack_next(
                        &owner, &owner, n_iter.value, n_iter.length)) == 0) {
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
                    const uint32_t owner_flag = 1U << (owner.tag - 0x80);
                    const uint32_t max_length = owner.tag == 0x80 ? 3U : EUICC_RSP_GID_BYTES;
                    if (mark_singleton_seen(&owner_seen, owner_flag) < 0 || owner.length > max_length ||
                        (owner.tag == 0x80 && owner.length != 3U))
                        goto err;
                }
                if (target != NULL && owner.length > 0) {
                    *target = malloc((owner.length * 2) + 1);
                    if (*target == NULL ||
                        euicc_hexutil_bin2hex(*target, (owner.length * 2) + 1, owner.value, owner.length) < 0)
                        goto err;
                }
            }
            if (owner_unpack_status < 0 || p->profileOwner.mccmnc == NULL)
                goto err;
            break;
        }
        case 0xB6:
            if (mark_singleton_seen(&seen_tags, SEEN_NOTIFICATION_CONFIG) < 0 ||
                n_iter.length > EUICC_RSP_NOTIFICATION_CONFIG_BYTES)
                goto err;
            euicc_apdu_unhandled_tag_print(NULL, &n_iter); // Metadata preview does not expose this field.
            break;
        case 0x99:
            if (mark_singleton_seen(&seen_tags, SEEN_POLICY_RULES) < 0 ||
                n_iter.length > EUICC_RSP_BIT_STRING_BYTES)
                goto err;
            euicc_apdu_unhandled_tag_print(NULL, &n_iter); // Assuming logging is not needed here
            break;
        }
    }
    if (metadata_unpack_status < 0)
        goto err;

    if ((seen_tags & (SEEN_ICCID | SEEN_PROVIDER_NAME | SEEN_PROFILE_NAME)) !=
        (SEEN_ICCID | SEEN_PROVIDER_NAME | SEEN_PROFILE_NAME)) {
        goto err;
    }

    *stru_metadata = p;
    p = NULL;
    ret = 0;
    goto exit;

err:
    ret = -1;
    es8p_metadata_free(&p);
exit:
    free(metadata);
    metadata = NULL;

    return ret;
}

void es8p_metadata_free(struct es8p_metadata **stru_metadata) {
    struct es8p_metadata *p = *stru_metadata;

    if (p == NULL) {
        return;
    }

    free(p->serviceProviderName);
    free(p->profileName);
    free(p->icon);
    free(p->profileOwner.mccmnc);
    free(p->profileOwner.gid1);
    free(p->profileOwner.gid2);
    free(p);

    *stru_metadata = NULL;
}
