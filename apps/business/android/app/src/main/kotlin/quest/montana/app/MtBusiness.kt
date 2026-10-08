package quest.montana.app

/**
 * THE BUSINESS CORE (the Rust crate mt-business) behind the JNI doors of native/montana-jni: each door is the same call of
 * the crate as its iOS twin mt_biz_* in mt-bindings/src/ffi_business.rs, proven equal by that library's tests on the Mac.
 * Nothing of the business is decided in Kotlin: who may do what, the fold of the two chains, the payroll's due and the
 * service's confirmation are the core's answers. A door answers null when the core refuses; nativeLastError() then names
 * the refusal by the core's own code (MT_ERR_BIZ_* of the C doors), 0 after a door that answered.
 *
 * A chain at a door is what the contract says: frames, each u32 length (little-endian) and one record.
 */
object MtBusiness {
    init { System.loadLibrary("montana") }

    /** The author's next record: sk[4032], pk[1952], the roster chain R, the staff chain H, the command (JSON), at_ms. */
    @JvmStatic external fun nativeAuthor(seckey: ByteArray, pubkey: ByteArray, roster: ByteArray, hr: ByteArray,
                                         command: String, atMs: Long): ByteArray?
    /** have and incoming joined by id, in the order (at_ms, id); every frame checked. */
    @JvmStatic external fun nativeMerge(have: ByteArray, incoming: ByteArray): ByteArray?
    /** The organization as the viewer sees it at now_ms: the view JSON of the contract. */
    @JvmStatic external fun nativeView(roster: ByteArray, hr: ByteArray, viewerPubkey: ByteArray, nowMs: Long): String?
    /** The records of one lane of H (lane[32]): what an employee is given of the staff chain. */
    @JvmStatic external fun nativeSlice(hr: ByteArray, lane: ByteArray): ByteArray?
    /** The service's confirmation of a number, checked under the pinned service key: number, subject, path, time (JSON). */
    @JvmStatic external fun nativeAttestOpen(attest: ByteArray): String?
    /** The service's pinned public key, 1952 bytes. */
    @JvmStatic external fun nativeServiceKey(): ByteArray?
    /** A member's id from the key they joined with: 32 bytes. */
    @JvmStatic external fun nativeMemberId(pubkey: ByteArray): ByteArray?
    /** The heads of a stream (contract 1.1): u32 count, then per lane in (chain, lane) order -- chain 1, lane 32, the highest place n u64, the digest 32. */
    @JvmStatic external fun nativeHeads(stream: ByteArray): ByteArray?
    /** What the holder of `heads` lacks of `stream`: a lane it does not name, whole; of a named lane, every record past its place (all, when the digests differ). */
    @JvmStatic external fun nativeAfter(stream: ByteArray, heads: ByteArray): ByteArray?
    /** A stream kept to one organization (org = its genesis id, 32 bytes): nothing of another organization is merged. */
    @JvmStatic external fun nativeKeep(org: ByteArray, stream: ByteArray): ByteArray?
    /** An invitation's id from its secret (32 bytes each): the id the view names it by (mt_biz_invite_id, the core's one door). */
    @JvmStatic external fun nativeInviteId(secret: ByteArray): ByteArray?
    /** The refusal of the last door called on this thread, by the core's code; 0 when it answered. */
    @JvmStatic external fun nativeLastError(): Int
}
