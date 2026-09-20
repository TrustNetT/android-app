package com.trustnetid.nfc

/**
 * Callback interface for real-time status updates from PassportReaderTD3.
 * Allows UI to display progress and errors to user.
 */
interface PassportReaderCallback {
    fun onStatus(message: String)
    fun onError(message: String)
}
