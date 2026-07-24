package com.trustnet.nfc

import android.nfc.tech.IsoDep
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import java.io.Serializable

/**
 * Adapter to wrap Android's IsoDep as a SCUBA CardService
 * This allows JMRTD PassportService to work with Android NFC
 */
class IsoDepCardServiceAdapter(private val isoDep: IsoDep) : CardService(), Serializable {
    
    private val TAG = "IsoDepAdapter"
    private var isOpen = false

    override fun open() {
        if (!isoDep.isConnected) {
            isoDep.connect()
        }
        isOpen = true
    }

    override fun close() {
        try {
            isoDep.close()
        } catch (e: Exception) {
            // Already closed or error, ignore
        }
        isOpen = false
    }

    override fun isOpen(): Boolean {
        return isOpen && isoDep.isConnected
    }

    override fun getATR(): ByteArray? {
        return null  // NFC doesn't have ATR like ISO/IEC 7816
    }

    override fun transmit(commandAPDU: CommandAPDU?): ResponseAPDU? {
        return try {
            val commandBytes = commandAPDU?.bytes ?: return null
            val responseBytes = isoDep.transceive(commandBytes)
            ResponseAPDU(responseBytes)
        } catch (e: Exception) {
            throw RuntimeException("APDU transmission failed: ${e.message}", e)
        }
    }

    override fun isConnectionLost(exception: Exception?): Boolean {
        // For NFC, connection is lost if IsoDep is no longer connected
        return !isoDep.isConnected
    }

    override fun toString(): String {
        return "IsoDepCardServiceAdapter"
    }
}
