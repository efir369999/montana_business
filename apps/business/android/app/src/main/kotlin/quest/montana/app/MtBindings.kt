package quest.montana.app

/**
 * The Montana core (Rust) — the one source of every derivation. Nothing here is
 * computed in Kotlin: the same words open the same keys on iOS and on Android because both
 * call the same code.
 *
 * Every function is a door of native/montana-jni: the same composition of the core's crates as
 * its iOS twin in mt-bindings/src/ffi_c.rs, proven equal by that crate's tests. There is no door
 * that turns entropy handed in by the app into a phrase: a person is born by the core alone.
 */
object MtBindings {
    init { System.loadLibrary("montana") }

    @JvmStatic external fun nativeAbiVersion(): Int
    @JvmStatic external fun nativeGenerateMnemonic(): String?
    @JvmStatic external fun nativeMnemonicToMasterSeed(mnemonic: String): ByteArray?
    @JvmStatic external fun nativeMnemonicToEntropy(mnemonic: String): ByteArray?
    /** 24 words → pk[1952] ‖ sk[4032]: the iOS door mt_seed_keys. */
    @JvmStatic external fun nativeSeedKeys(mnemonic: String): ByteArray?
    /** master_seed[64] → pk[1952] ‖ sk[4032] with no second stretch: the iOS door mt_seed_keys_from_master. */
    @JvmStatic external fun nativeSeedKeysFromMaster(master: ByteArray): ByteArray?

    /** master_seed[64] + role → 32 bytes: the core's mt_mldsa_seed_for_role. */
    @JvmStatic external fun nativeRoleSeed(master: ByteArray, role: String): ByteArray?

    /** Bytes drawn by the core from its own sources (mt_random_fast), 1..4096; null when the core refuses. */
    @JvmStatic external fun nativeRandom(len: Int): ByteArray?
    /** entropy[32] → history_key[32] (mt_history_key). */
    @JvmStatic external fun nativeHistoryKey(entropy: ByteArray): ByteArray?
    /** Which conversation a sealed history block belongs to, 32 bytes, or null (mt_archive_peek_conv). */
    @JvmStatic external fun nativeArchivePeekConv(hk: ByteArray, owner: ByteArray, sealed: ByteArray): ByteArray?
    /** A sealed block filed into <base>/Chats/<chat>: 1 appended, 0 already held, <0 refused (mt_archive_ingest). */
    @JvmStatic external fun nativeArchiveIngest(base: String, chat: String, hk: ByteArray, owner: ByteArray, sealed: ByteArray): Int

    /** seed[64] → a card's key, pk[1184] ‖ sk[2400] (mt_mlkem_keypair_from_seed). */
    @JvmStatic external fun nativeMlkemKeypair(seed: ByteArray): ByteArray?
    /** key[32], nonce[12], input → nonce ‖ sealed, the node's blob (mt_e2e_seal_blob). */
    @JvmStatic external fun nativeSealBlob(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray?
    /** key[32], a sealed blob → its bytes, or null when the key is not its key (mt_e2e_open_blob). */
    @JvmStatic external fun nativeOpenBlob(key: ByteArray, sealed: ByteArray): ByteArray?
    /** sk[2400], ct[1088] → ss[32], implicit rejection: a foreign ct yields garbage, never null (mt_mlkem_decaps). */
    @JvmStatic external fun nativeMlkemDecaps(sk: ByteArray, ct: ByteArray): ByteArray?
    /** pk[1184] → ct[1088] ‖ ss[32]: the scanner's one encapsulation to a card's key (mt_mlkem_encaps). */
    @JvmStatic external fun nativeMlkemEncaps(pk: ByteArray): ByteArray?
    /** ss, contact root, ct → the first letter's secret[32] (mt_name_first_secret). */
    @JvmStatic external fun nativeFirstSecret(ss: ByteArray, root: ByteArray, ct: ByteArray): ByteArray?

    const val PUBKEY = 1952
    const val SECKEY = 4032
}
