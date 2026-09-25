package app.hyperlpa.data

import app.hyperlpa.R
import app.hyperlpa.domain.model.DownloadRequestError
import app.hyperlpa.domain.model.DownloadRequestException

internal fun downloadRequestErrorResource(error: Throwable): Int =
    when ((error as? DownloadRequestException)?.reason) {
        DownloadRequestError.CONFIRMATION_CODE_TOO_LONG -> R.string.activation_error_confirmation_too_long
        DownloadRequestError.CONFIRMATION_CODE_INVALID -> R.string.activation_error_confirmation_invalid
        DownloadRequestError.SMDP_ADDRESS_REQUIRED -> R.string.activation_error_address_required
        DownloadRequestError.ACTIVATION_CODE_TOO_LONG -> R.string.activation_error_too_long
        DownloadRequestError.ACTIVATION_CODE_FIELDS -> R.string.activation_error_fields
        DownloadRequestError.ACTIVATION_CODE_VERSION -> R.string.activation_error_version
        DownloadRequestError.ACTIVATION_CODE_ADDRESS_MISSING -> R.string.activation_error_address_missing
        DownloadRequestError.MATCHING_ID_TOO_LONG -> R.string.activation_error_matching_too_long
        DownloadRequestError.MATCHING_ID_INVALID -> R.string.activation_error_matching_invalid
        DownloadRequestError.SMDP_OID_INVALID -> R.string.activation_error_oid_invalid
        DownloadRequestError.CONFIRMATION_FLAG_INVALID -> R.string.activation_error_confirmation_flag
        DownloadRequestError.RSP_ADDRESS_REQUIRED -> R.string.activation_error_rsp_required
        DownloadRequestError.RSP_ADDRESS_TOO_LONG -> R.string.activation_error_rsp_too_long
        DownloadRequestError.RSP_ADDRESS_HAS_SCHEME -> R.string.activation_error_rsp_scheme
        DownloadRequestError.RSP_ADDRESS_WHITESPACE -> R.string.activation_error_rsp_whitespace
        DownloadRequestError.RSP_ADDRESS_UNSUPPORTED_CHARACTERS -> R.string.activation_error_rsp_characters
        DownloadRequestError.RSP_ADDRESS_INVALID -> R.string.activation_error_rsp_invalid
        DownloadRequestError.RSP_PORT_INVALID -> R.string.activation_error_rsp_port
        null -> R.string.activation_error_invalid
    }
