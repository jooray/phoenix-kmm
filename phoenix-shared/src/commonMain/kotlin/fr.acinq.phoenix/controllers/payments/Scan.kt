package fr.acinq.phoenix.controllers.payments

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.payment.PaymentRequest
import fr.acinq.phoenix.data.Chain
import fr.acinq.phoenix.controllers.MVI

data class LnUrlAuth(
    val domain: String,
    val k1: ByteVector32,
    val action: Action?
) {
    enum class Action {
        Register,
        Login,
        Link,
        Auth;

        companion object {
            fun parse(str: String) = when (str.toLowerCase()) {
                "register" -> Register
                "login" -> Login
                "link" -> Link
                "auth" -> Auth
                else -> null
            }
        }
    }
}

object Scan {

    sealed class BadRequestReason {
        data class ChainMismatch(val myChain: Chain, val requestChain: Chain?): BadRequestReason()
        data class UnsupportedLnUrl(val hrp: String, val data: String, val wtf: Int): BadRequestReason()
        object IsBitcoinAddress: BadRequestReason()
        object UnknownFormat: BadRequestReason()
        object AlreadyPaidInvoice: BadRequestReason()
    }

    sealed class DangerousRequestReason {
        object IsAmountlessInvoice: DangerousRequestReason()
        object IsOwnInvoice: DangerousRequestReason()
    }

    sealed class Model : MVI.Model() {
        object Ready: Model()
        data class BadRequest(
            val reason: BadRequestReason
        ): Model()
        data class DangerousRequest(
            val reason: DangerousRequestReason,
            val request: String,
            val paymentRequest: PaymentRequest
        ): Model()
        data class ValidateRequest(
            val request: String,
            val paymentRequest: PaymentRequest,
            val amountMsat: Long?,
            val expiryTimestamp: Long?, // since unix epoch
            val requestDescription: String?,
            val balanceMsat: Long
        ): Model()
        object Sending: Model()
        data class LoginRequest(
            val request: String,
            val auth: LnUrlAuth
        ): Model()
    }

    sealed class Intent : MVI.Intent() {
        data class Parse(
            val request: String
        ) : Intent()
        data class ConfirmDangerousRequest(
            val request: String,
            val paymentRequest: PaymentRequest
        ) : Intent()
        data class Send(
            val paymentRequest: PaymentRequest,
            val amount: MilliSatoshi
        ) : Intent()
    }

}
