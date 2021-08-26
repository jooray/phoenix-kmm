package fr.acinq.phoenix.data

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.Digest
import fr.acinq.bitcoin.crypto.hmac
import io.ktor.utils.io.bits.*
import io.ktor.utils.io.core.*


data class Wallet(val seed: ByteVector64, val chain: Chain) {

    private val master by lazy { DeterministicWallet.generate(seed) }

    fun masterPublicKey(path: String): String {
        val publicKey =
            DeterministicWallet.publicKey(
                DeterministicWallet.derivePrivateKey(master, path)
            )
        return DeterministicWallet.encode(
            input = publicKey,
            prefix = if (chain.isMainnet()) DeterministicWallet.zpub else DeterministicWallet.vpub
        )
    }

    /** Get the wallet (xpub, path) */
    fun xpub(): Pair<String, String> {
        val isMainnet = chain.isMainnet()
        val masterPubkeyPath = if (isMainnet) "m/84'/0'/0'" else "m/84'/1'/0'"
        return masterPublicKey(masterPubkeyPath) to masterPubkeyPath
    }

    fun onchainAddress(path: String): String {
        val isMainnet = chain.isMainnet()
        val chainHash = if (isMainnet) Block.LivenetGenesisBlock.hash else Block.TestnetGenesisBlock.hash
        val publicKey = DeterministicWallet.derivePrivateKey(master, path).publicKey
        return Bitcoin.computeBIP84Address(publicKey, chainHash)
    }

    // For cloud storage, we need:
    // - an encryption key
    // - a cleartext string that's tied to a specific nodeId
    //
    // But we also don't want to expose the nodeId.
    // So we deterministically derive both values from the seed.
    //
    fun cloudKeyAndEncryptedNodeId(): Pair<ByteVector32, String> {
        val path = if (chain.isMainnet()) "m/51'/0'/0'/0" else "m/51'/1'/0'/0"
        val extPrivKey = DeterministicWallet.derivePrivateKey(master, path)

        val cloudKey = extPrivKey.privateKey.value
        val hash = Crypto.hash160(cloudKey).byteVector().toHex()

        return Pair(cloudKey, hash)
    }

    fun lnurlAuthLinkingKey(domain: String): PrivateKey {
        val hashingKey = DeterministicWallet.derivePrivateKey(master, "m/138'/0")
        val fullHash = Digest.sha256().hmac(
            key = hashingKey.privateKey.value.toByteArray(),
            data = domain.encodeToByteArray(),
            blockSize = 64
        )
        require(fullHash.size >= 16) { "domain hash must be at least 16 bytes" }
        val path1 = fullHash.sliceArray(IntRange(0, 3)).readInt(0, ByteOrder.BIG_ENDIAN).toLong()
        val path2 = fullHash.sliceArray(IntRange(4, 7)).readInt(0, ByteOrder.BIG_ENDIAN).toLong()
        val path3 = fullHash.sliceArray(IntRange(8, 11)).readInt(0, ByteOrder.BIG_ENDIAN).toLong()
        val path4 = fullHash.sliceArray(IntRange(12, 15)).readInt(0, ByteOrder.BIG_ENDIAN).toLong()

        val path = KeyPath("m/138'/${path1}/${path2}/${path3}/${path4}")
        return DeterministicWallet.derivePrivateKey(master, path).privateKey
    }

    override fun toString(): String = "Wallet"
}

fun ByteArray.readInt(index: Int, order: ByteOrder): Int {
    // According to the docs for `ByteArray.getIntAt(index: Int)`:
    // > [These operations] extract primitive values out of the [ByteArray] byte buffers.
    // > Data is treated as if it was in Least-Significant-Byte first (little-endian) byte order.
    val littleEndian = this.getIntAt(index)
    return when (order) {
        ByteOrder.LITTLE_ENDIAN -> littleEndian
        ByteOrder.BIG_ENDIAN -> littleEndian.reverseByteOrder()
    }
}
