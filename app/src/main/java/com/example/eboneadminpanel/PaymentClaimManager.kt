package com.example.eboneadminpanel

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.security.MessageDigest
import java.util.Locale

/**
 * Central one-time payment claim gate.
 *
 * Customer and Dealer SMS flows will call this manager AFTER their existing
 * matching logic finds a candidate payment. This manager decides whether the
 * real payment can be consumed for the first time or is a duplicate attempt.
 *
 * No UI/layout code lives here.
 */
object PaymentClaimManager {

    private const val TAG = "PaymentClaimManager"
    private const val CLAIMS_COLLECTION = "paymentClaims"
    private const val DUPLICATE_COLLECTION = "paymentDuplicateAttempts"

    const val OWNER_CUSTOMER = "CUSTOMER"
    const val OWNER_DEALER = "DEALER"

    data class ClaimRequest(
        val ownerType: String,
        val ownerId: String,
        val ownerName: String = "",
        val paymentSource: String = "",
        val identifierType: String = "",
        val identifier: String = "",
        val amount: Double = 0.0,
        val submittedAt: Long = 0L,
        val smsTimestamp: Long = 0L,
        val transactionId: String = ""
    )

    sealed class ClaimResult {
        data class Claimed(val claimId: String) : ClaimResult()

        data class Duplicate(
            val claimId: String,
            val originalOwnerType: String,
            val originalOwnerId: String,
            val originalOwnerName: String,
            val originalTransactionId: String
        ) : ClaimResult()

        data class Invalid(val reason: String) : ClaimResult()

        data class Failed(val error: Exception) : ClaimResult()
    }

    /**
     * Atomically claims one real incoming payment.
     *
     * Preferred identity:
     *   receiving source + identifier type + identifier
     *
     * Fallback when no TID/reference exists:
     *   receiving source + amount + exact SMS fingerprint
     *
     * Amount alone is NEVER used as a duplicate key.
     */
    fun claim(
        request: ClaimRequest,
        smsBody: String,
        onResult: (ClaimResult) -> Unit
    ) {
        val ownerType = request.ownerType.trim().uppercase(Locale.getDefault())

        if (ownerType != OWNER_CUSTOMER && ownerType != OWNER_DEALER) {
            onResult(
                ClaimResult.Invalid(
                    "Unknown owner type: ${request.ownerType}"
                )
            )
            return
        }

        val source = normalizeSource(request.paymentSource)
        val identifierType = normalizeIdentifierType(request.identifierType)
        val identifier = normalizeIdentifier(request.identifier)

        val claimId = buildClaimId(
            source = source,
            identifierType = identifierType,
            identifier = identifier,
            amount = request.amount,
            smsBody = smsBody
        )

        val db = FirebaseFirestore.getInstance()
        val claimRef = db.collection(CLAIMS_COLLECTION).document(claimId)

        db.runTransaction { transaction ->
            val existing = transaction.get(claimRef)

            if (existing.exists()) {
                return@runTransaction ClaimResult.Duplicate(
                    claimId = claimId,
                    originalOwnerType =
                        existing.getString("ownerType").orEmpty(),
                    originalOwnerId =
                        existing.getString("ownerId").orEmpty(),
                    originalOwnerName =
                        existing.getString("ownerName").orEmpty(),
                    originalTransactionId =
                        existing.getString("transactionId").orEmpty()
                )
            }

            val claimData = hashMapOf<String, Any>(
                "ownerType" to ownerType,
                "ownerId" to request.ownerId,
                "ownerName" to request.ownerName,
                "paymentSource" to source,
                "identifierType" to identifierType,
                "identifier" to identifier,
                "amount" to request.amount,
                "submittedAt" to request.submittedAt,
                "smsTimestamp" to request.smsTimestamp,
                "transactionId" to request.transactionId,
                "smsFingerprint" to sha256(smsBody),
                "claimedAt" to FieldValue.serverTimestamp(),
                "status" to "CLAIMED"
            )

            transaction.set(claimRef, claimData)
            ClaimResult.Claimed(claimId)
        }.addOnSuccessListener { result ->
            if (result is ClaimResult.Duplicate) {
                logDuplicateAttempt(
                    request = request,
                    normalizedSource = source,
                    normalizedIdentifierType = identifierType,
                    normalizedIdentifier = identifier,
                    result = result,
                    smsBody = smsBody
                )
            }

            onResult(result)
        }.addOnFailureListener { error ->
            Log.e(
                TAG,
                "Payment claim failed for transaction ${request.transactionId}",
                error
            )
            onResult(ClaimResult.Failed(error))
        }
    }

    /**
     * Registers a legacy payment that has already been verified.
     *
     * This does NOT change the original transaction or balance.
     * It simply creates the central claim record so a later Customer/Dealer
     * attempt can be recognized as a duplicate.
     */
    fun registerExistingClaim(
        request: ClaimRequest,
        smsBody: String,
        onResult: (ClaimResult) -> Unit = {}
    ) {
        val source = normalizeSource(request.paymentSource)
        val identifierType = normalizeIdentifierType(request.identifierType)
        val identifier = normalizeIdentifier(request.identifier)

        val claimId = buildClaimId(
            source = source,
            identifierType = identifierType,
            identifier = identifier,
            amount = request.amount,
            smsBody = smsBody
        )

        val claimRef =
            FirebaseFirestore.getInstance()
                .collection(CLAIMS_COLLECTION)
                .document(claimId)

        claimRef.get()
            .addOnSuccessListener { existing ->
                if (existing.exists()) {
                    onResult(
                        ClaimResult.Duplicate(
                            claimId = claimId,
                            originalOwnerType =
                                existing.getString("ownerType").orEmpty(),
                            originalOwnerId =
                                existing.getString("ownerId").orEmpty(),
                            originalOwnerName =
                                existing.getString("ownerName").orEmpty(),
                            originalTransactionId =
                                existing.getString("transactionId").orEmpty()
                        )
                    )
                    return@addOnSuccessListener
                }

                val claimData = hashMapOf<String, Any>(
                    "ownerType" to request.ownerType.uppercase(Locale.getDefault()),
                    "ownerId" to request.ownerId,
                    "ownerName" to request.ownerName,
                    "paymentSource" to source,
                    "identifierType" to identifierType,
                    "identifier" to identifier,
                    "amount" to request.amount,
                    "submittedAt" to request.submittedAt,
                    "smsTimestamp" to request.smsTimestamp,
                    "transactionId" to request.transactionId,
                    "smsFingerprint" to sha256(smsBody),
                    "claimedAt" to FieldValue.serverTimestamp(),
                    "status" to "CLAIMED"
                )

                claimRef.set(claimData)
                    .addOnSuccessListener {
                        onResult(ClaimResult.Claimed(claimId))
                    }
                    .addOnFailureListener { error ->
                        onResult(ClaimResult.Failed(error))
                    }
            }
            .addOnFailureListener { error ->
                onResult(ClaimResult.Failed(error))
            }
    }

    private fun logDuplicateAttempt(
        request: ClaimRequest,
        normalizedSource: String,
        normalizedIdentifierType: String,
        normalizedIdentifier: String,
        result: ClaimResult.Duplicate,
        smsBody: String
    ) {
        val payload = hashMapOf<String, Any>(
            "amount" to request.amount,
            "paymentSource" to normalizedSource,
            "identifierType" to normalizedIdentifierType,
            "identifier" to normalizedIdentifier,
            "duplicateOwnerType" to request.ownerType.uppercase(Locale.getDefault()),
            "duplicateOwnerId" to request.ownerId,
            "duplicateOwnerName" to request.ownerName,
            "duplicateTransactionId" to request.transactionId,
            "originalOwnerType" to result.originalOwnerType,
            "originalOwnerId" to result.originalOwnerId,
            "originalOwnerName" to result.originalOwnerName,
            "originalTransactionId" to result.originalTransactionId,
            "detectedAt" to FieldValue.serverTimestamp(),
            "smsFingerprint" to sha256(smsBody),
            "status" to "DUPLICATE_ATTEMPT"
        )

        FirebaseFirestore.getInstance()
            .collection(DUPLICATE_COLLECTION)
            .add(payload)
            .addOnFailureListener { error ->
                Log.e(
                    TAG,
                    "Duplicate attempt log failed",
                    error
                )
            }
    }

    private fun buildClaimId(
        source: String,
        identifierType: String,
        identifier: String,
        amount: Double,
        smsBody: String
    ): String {
        val rawIdentity = if (identifier.isNotBlank()) {
            // CRITICAL: source is deliberately NOT part of this key.
            // The whole point of a TID/Reference match is that it's the
            // real bank/wallet transaction id — it must be treated as
            // globally unique across the ENTIRE system regardless of
            // whether the customer flow labeled it "EasyPaisa" and the
            // dealer flow labeled it "DEALER_TOPUP". Including source
            // here would let the exact same real transaction be claimed
            // once per differently-labeled source, defeating the
            // customer-vs-dealer duplicate check entirely.
            "ID|$identifierType|$identifier"
        } else {
            // The SMS-fingerprint fallback (no identifier available) has
            // no such global identity to rely on, so source still helps
            // narrow an otherwise loose amount+fingerprint match.
            val normalizedAmount =
                String.format(Locale.US, "%.2f", amount)

            "SMS|$source|$normalizedAmount|${sha256(smsBody)}"
        }

        return sha256(rawIdentity)
    }

    private fun normalizeIdentifierType(value: String): String {
        val key = value.trim()
            .uppercase(Locale.getDefault())
            .replace(" ", "_")
            .replace("-", "_")

        return when (key) {
            "TID",
            "TRANSACTION_ID",
            "TRANSACTIONID",
            "TXN_ID",
            "TXNID",
            "TRX_ID",
            "TRXID" -> "TID"

            "REFERENCE",
            "REFERENCE_ID",
            "REFERENCE_NUMBER",
            "REFERENCE_NO",
            "REF",
            "REF_ID",
            "REF_NO" -> "REFERENCE"

            "" -> "UNKNOWN"

            else -> key
        }
    }

    private fun normalizeIdentifier(value: String): String =
        value.trim()
            .replace("\\s+".toRegex(), "")
            .uppercase(Locale.getDefault())

    private fun normalizeSource(value: String): String =
        value.trim()
            .uppercase(Locale.getDefault())
            .replace(" ", "_")
            .replace("-", "_")

    private fun sha256(value: String): String {
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))

        return bytes.joinToString("") {
            "%02x".format(it)
        }
    }
}