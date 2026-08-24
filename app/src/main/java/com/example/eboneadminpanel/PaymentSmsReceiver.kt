package com.example.eboneadminpanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar
import kotlin.math.abs

/**
 * Customer payment SMS receiver.
 *
 * Existing customer matching flow is preserved:
 *  - TID / transaction ID
 *  - Reference ID / reference number
 *  - amount + time + sender-name fallback
 *
 * NEW:
 *  - Every matched customer payment must pass through PaymentClaimManager.
 *  - This prevents the same real payment from later being used by Dealer.
 *  - Today's / configured match-window rule remains based on the customer's
 *    transaction creation time.
 *  - No UI/layout changes are made here.
 */
class PaymentSmsReceiver : BroadcastReceiver() {

    private val labeledIdentifierRegex = Regex(
        """(?:T-?ID|Txn\s?ID|Trx\s?No|Reference(?:\s?(?:ID|No|Number))?)[:\s#]*([A-Za-z0-9]+)""",
        RegexOption.IGNORE_CASE
    )

    private val standaloneDigitTidRegex =
        Regex("""\b(\d{12,14})\b""")

    private val amountRegex = Regex(
        """(?:Rs\.?|PKR)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    private val matchTimeWindowMs = 10 * 60 * 1000L

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages =
            Telephony.Sms.Intents.getMessagesFromIntent(intent)

        for (msg in messages) {
            val body = msg.messageBody ?: continue
            processSms(context.applicationContext, body)
        }
    }

    private fun matchWindowStartMillis(
        context: Context
    ): Long {
        val days =
            SmsMatchSettingsActivity.getMatchWindowDays(context)
                .coerceAtLeast(1)

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // days=1 => today only
        cal.add(
            Calendar.DAY_OF_YEAR,
            -(days - 1)
        )

        return cal.timeInMillis
    }

    private fun processSms(
        context: Context,
        body: String
    ) {
        val identifier =
            extractIdentifier(body)

        if (identifier != null) {
            checkIdentifierMatch(
                context = context,
                identifier = identifier,
                smsBody = body
            )
        } else {
            checkAmountTimeNameFallback(
                context = context,
                smsBody = body
            )
        }
    }

    private fun extractIdentifier(
        smsBody: String
    ): Pair<String, String>? {
        val labeled =
            labeledIdentifierRegex.find(smsBody)

        if (labeled != null) {
            val value =
                labeled.groupValues
                    .getOrNull(1)
                    ?.trim()
                    .orEmpty()

            if (value.isNotBlank()) {
                val label =
                    labeled.value
                        .substringBefore(value)
                        .trim()
                        .uppercase()

                val type =
                    if (
                        label.contains("REFERENCE")
                    ) {
                        "REFERENCE"
                    } else {
                        "TID"
                    }

                return type to value
            }
        }

        val standalone =
            standaloneDigitTidRegex.find(smsBody)
                ?.groupValues
                ?.getOrNull(1)

        return standalone?.let {
            "TID" to it
        }
    }

    fun extractTid(
        smsBody: String
    ): String? {
        return extractIdentifier(smsBody)
            ?.takeIf { it.first == "TID" }
            ?.second
    }

    private fun extractAmount(
        smsBody: String
    ): Double? {
        val match =
            amountRegex.find(smsBody)
                ?: return null

        return match.groupValues[1]
            .replace(",", "")
            .toDoubleOrNull()
    }

    /**
     * Try the exact identifier in the existing customer transaction.
     *
     * First we use the current bankTransactionId field.
     * If it isn't found and the identifier is a reference number, we also
     * check the referenceNumber / referenceId fields already supported by
     * the central claim model.
     */
    private fun checkIdentifierMatch(
        context: Context,
        identifier: Pair<String, String>,
        smsBody: String
    ) {
        val db =
            FirebaseFirestore.getInstance()

        val type = identifier.first
        val value = identifier.second

        val query =
            db.collection("transactions")
                .whereEqualTo(
                    "status",
                    "PENDING"
                )
                .whereGreaterThanOrEqualTo(
                    "createdAt",
                    matchWindowStartMillis(context)
                )

        query.get()
            .addOnSuccessListener { snapshot ->

                val match =
                    snapshot.documents.firstOrNull { doc ->
                        matchesIdentifier(
                            doc = doc,
                            identifierType = type,
                            identifier = value
                        )
                    }

                if (match != null) {
                    claimCustomerPayment(
                        context = context,
                        document = match,
                        identifierType = type,
                        identifier = value,
                        smsBody = smsBody
                    )
                } else {
                    checkAmountTimeNameFallback(
                        context = context,
                        smsBody = smsBody
                    )
                }
            }
            .addOnFailureListener { error ->
                Log.e(
                    "PaymentSmsReceiver",
                    "Firestore identifier lookup failed",
                    error
                )
            }
    }

    private fun matchesIdentifier(
        doc: DocumentSnapshot,
        identifierType: String,
        identifier: String
    ): Boolean {
        val normalized =
            identifier
                .trim()
                .replace("\\s+".toRegex(), "")
                .uppercase()

        val bankTid =
            doc.getString("bankTransactionId")
                .orEmpty()
                .replace("\\s+".toRegex(), "")
                .uppercase()

        if (bankTid == normalized) {
            return true
        }

        if (identifierType == "REFERENCE") {
            val referenceFields =
                listOf(
                    "referenceNumber",
                    "referenceId",
                    "reference",
                    "bankReferenceNumber",
                    "transactionReference"
                )

            for (field in referenceFields) {
                val value =
                    doc.getString(field)
                        ?.replace("\\s+".toRegex(), "")
                        ?.uppercase()

                if (!value.isNullOrBlank() &&
                    value == normalized
                ) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Existing amount/time/name fallback is preserved.
     *
     * IMPORTANT:
     * Amount alone is NOT enough for duplicate protection.
     * Once a customer candidate is found, PaymentClaimManager is the final
     * one-time-payment gate.
     */
    private fun checkAmountTimeNameFallback(
        context: Context,
        smsBody: String
    ) {
        val amount =
            extractAmount(smsBody)
                ?: return

        val now =
            System.currentTimeMillis()

        FirebaseFirestore.getInstance()
            .collection("transactions")
            .whereEqualTo(
                "status",
                "PENDING"
            )
            .whereEqualTo(
                "amount",
                amount
            )
            .whereGreaterThanOrEqualTo(
                "createdAt",
                matchWindowStartMillis(context)
            )
            .get()
            .addOnSuccessListener { snapshot ->

                for (doc in snapshot.documents) {

                    val createdAt =
                        doc.getLong("createdAt")
                            ?: continue

                    if (
                        abs(now - createdAt) >
                        matchTimeWindowMs
                    ) {
                        continue
                    }

                    val senderName =
                        doc.getString("senderName")

                    if (
                        !senderName.isNullOrBlank() &&
                        !smsBody.contains(
                            senderName,
                            ignoreCase = true
                        )
                    ) {
                        continue
                    }

                    claimCustomerPayment(
                        context = context,
                        document = doc,
                        identifierType =
                            inferStoredIdentifierType(doc),
                        identifier =
                            inferStoredIdentifier(doc),
                        smsBody = smsBody
                    )

                    return@addOnSuccessListener
                }
            }
            .addOnFailureListener { error ->
                Log.e(
                    "PaymentSmsReceiver",
                    "Firestore amount/time lookup failed",
                    error
                )
            }
    }

    private fun inferStoredIdentifierType(
        document: DocumentSnapshot
    ): String {
        val reference =
            listOf(
                "referenceNumber",
                "referenceId",
                "reference",
                "bankReferenceNumber",
                "transactionReference"
            ).firstOrNull {
                !document
                    .getString(it)
                    .isNullOrBlank()
            }

        return if (reference != null) {
            "REFERENCE"
        } else {
            "TID"
        }
    }

    private fun inferStoredIdentifier(
        document: DocumentSnapshot
    ): String {
        val referenceFields =
            listOf(
                "referenceNumber",
                "referenceId",
                "reference",
                "bankReferenceNumber",
                "transactionReference"
            )

        for (field in referenceFields) {
            val reference =
                document
                    .getString(field)
                    ?.trim()
                    .orEmpty()

            if (reference.isNotBlank()) {
                return reference
            }
        }

        return document
            .getString("bankTransactionId")
            .orEmpty()
    }

    /**
     * Central payment gate.
     *
     * Claimed:
     *   existing Customer activation notification continues unchanged.
     *
     * Duplicate:
     *   PaymentClaimManager records the original owner + this attempted
     *   customer payment and NO activation notification is shown.
     *
     * Failed:
     *   No activation is triggered.
     */
    private fun claimCustomerPayment(
        context: Context,
        document: DocumentSnapshot,
        identifierType: String,
        identifier: String,
        smsBody: String
    ) {
        val customerId =
            document.getString("customerId")
                ?: return

        val customerName =
            document.getString("customerName")
                ?: document.getString("name")
                ?: customerId

        val source =
            document.getString("source")
                ?: document.getString("paymentSource")
                ?: document.getString("method")
                ?: ""

        val amount =
            document.getDouble("amount")
                ?: 0.0

        val createdAt =
            document.getLong("createdAt")
                ?: 0L

        PaymentClaimManager.claim(
            PaymentClaimManager.ClaimRequest(
                ownerType =
                    PaymentClaimManager.OWNER_CUSTOMER,
                ownerId = customerId,
                ownerName = customerName,
                paymentSource = source,
                identifierType = identifierType,
                identifier = identifier,
                amount = amount,
                submittedAt = createdAt,
                smsTimestamp =
                    System.currentTimeMillis(),
                transactionId = document.id
            ),
            smsBody = smsBody
        ) { result ->

            when (result) {

                is PaymentClaimManager.ClaimResult.Claimed -> {
                    /*
                     * Existing Customer flow remains exactly here:
                     * the existing notification/service path continues to
                     * process this Customer transaction.
                     */
                    PaymentNotificationHelper.showActivationNotification(
                        context,
                        document.id,
                        customerId
                    )
                }

                is PaymentClaimManager.ClaimResult.Duplicate -> {
                    Log.w(
                        "PaymentSmsReceiver",
                        "DUPLICATE CUSTOMER PAYMENT REJECTED: " +
                                "customer=$customerId, " +
                                "original=${result.originalOwnerType}:" +
                                result.originalOwnerId +
                                ", transaction=${document.id}"
                    )
                }

                is PaymentClaimManager.ClaimResult.Invalid -> {
                    Log.e(
                        "PaymentSmsReceiver",
                        "Customer payment claim invalid: " +
                                result.reason
                    )
                }

                is PaymentClaimManager.ClaimResult.Failed -> {
                    Log.e(
                        "PaymentSmsReceiver",
                        "Customer payment claim failed",
                        result.error
                    )
                }
            }
        }
    }
}