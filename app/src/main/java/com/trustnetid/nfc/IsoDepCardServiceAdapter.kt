package com.trustnetid.nfc

import android.nfc.tech.IsoDep
import android.util.Log
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
            
            // Log the exact APDU bytes being transmitted
            val hexString = commandBytes.joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "→ TX APDU (${commandBytes.size} bytes): $hexString")
            
            // Parse APDU structure for debugging
            if (commandBytes.isNotEmpty()) {
                val cla = String.format("%02X", commandBytes[0])
                val ins = if (commandBytes.size > 1) String.format("%02X", commandBytes[1]) else "??"
                val p1 = if (commandBytes.size > 2) String.format("%02X", commandBytes[2]) else "??"
                val p2 = if (commandBytes.size > 3) String.format("%02X", commandBytes[3]) else "??"
                Log.d(TAG, "  CLA=$cla INS=$ins P1=$p1 P2=$p2")
            }
            
            val responseBytes = isoDep.transceive(commandBytes)
            
            // Log the response
            val rxHexString = responseBytes.joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "← RX APDU (${responseBytes.size} bytes): $rxHexString")
            
            // Extract status word
            if (responseBytes.size >= 2) {
                val sw = ((responseBytes[responseBytes.size - 2].toInt() and 0xFF) shl 8) or 
                         (responseBytes[responseBytes.size - 1].toInt() and 0xFF)
                Log.d(TAG, "  Status Word: 0x${sw.toString(16).padStart(4, '0')}")
            }
            
            ResponseAPDU(responseBytes)
        } catch (e: Exception) {
            Log.e(TAG, "✗ APDU transmission failed: ${e.message}", e)
            throw RuntimeException("APDU transmission failed: ${e.message}", e)
        }
    }

    override fun isConnectionLost(exception: Exception?): Boolean {
        // For NFC, connection is lost if IsoDep is no longer connected
        return !isoDep.isConnected
    }

    /**
     * Direct manual GET CHALLENGE test (bypass JMRTD)
     * Used for debugging: if this works but JMRTD doBAC fails, issue is in JMRTD/CardService wiring
     */
    fun testManualGetChallenge(): ByteArray? {
        return try {
            Log.d(TAG, "\n=== MANUAL GET CHALLENGE TEST ===")
            Log.d(TAG, "Bypassing JMRTD to test chip directly...")
            
            // GET CHALLENGE per ISO 7816-4:
            // CLA=0x00, INS=0x84 (GET CHALLENGE), P1=0x00, P2=0x00, Le=0x08
            val getChallengApdu = byteArrayOf(
                0x00.toByte(),  // CLA (iso standard)
                0x84.toByte(),  // INS (GET CHALLENGE)
                0x00.toByte(),  // P1
                0x00.toByte(),  // P2
                0x08.toByte()   // Le (expected 8-byte challenge)
            )
            
            Log.d(TAG, "Manual APDU: ${getChallengApdu.joinToString(" ") { "%02X".format(it) }}")
            val response = isoDep.transceive(getChallengApdu)
            
            Log.d(TAG, "Response (${response.size} bytes): ${response.joinToString(" ") { "%02X".format(it) }}")
            
            if (response.size >= 2) {
                val sw = ((response[response.size - 2].toInt() and 0xFF) shl 8) or 
                         (response[response.size - 1].toInt() and 0xFF)
                Log.d(TAG, "Status Word: 0x${sw.toString(16).padStart(4, '0')}")
                
                when (sw) {
                    0x9000 -> {
                        Log.d(TAG, "✓ Manual GET CHALLENGE SUCCESS - chip responds correctly")
                        Log.d(TAG, "  8-byte challenge: ${response.dropLast(2).joinToString(" ") { "%02X".format(it) }}")
                        Log.d(TAG, "→ This means: Chip is fine, problem is JMRTD/CardService wiring")
                    }
                    0x6D00 -> {
                        Log.e(TAG, "✗ Manual GET CHALLENGE also got 0x6D00")
                        Log.e(TAG, "  This means: Chip state issue or SELECT AID failed")
                        Log.e(TAG, "  Recommended: Verify SELECT AID succeeded (check logs above)")
                    }
                    else -> {
                        Log.w(TAG, "⚠ Manual GET CHALLENGE got unexpected SW: 0x${sw.toString(16).padStart(4, '0')}")
                    }
                }
            }
            
            Log.d(TAG, "=== END MANUAL TEST ===")
            response
        } catch (e: Exception) {
            Log.e(TAG, "✗ Manual GET CHALLENGE test failed: ${e.message}", e)
            null
        }
    }

    override fun toString(): String {
        return "IsoDepCardServiceAdapter"
    }
}
