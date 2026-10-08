//! The Montana core for Android: the doors the client path needs, each one the same composition of
//! the core's crates as its iOS twin in mt-bindings/src/ffi_c.rs. No derivation is written here —
//! every quantity is computed by mt-mnemonic and mt-crypto; this file only carries bytes across JNI.
//! `tests` below prove each door against the iOS door on the Mac.

use mt_codec::domain;
use mt_crypto::keypair_from_seed;
use mt_mnemonic::{mldsa_seed_for_role, mnemonic_to_entropy, mnemonic_to_master_seed};
use zeroize::Zeroizing;

pub const ABI_VERSION: u32 = parse_u32(env!("MT_ABI_VERSION"));
pub const PUBKEY_LEN: usize = 1952;
pub const SECKEY_LEN: usize = 4032;

const fn parse_u32(s: &str) -> u32 {
    let b = s.as_bytes();
    let mut i = 0;
    let mut v = 0u32;
    while i < b.len() {
        v = v * 10 + (b[i] - b'0') as u32;
        i += 1;
    }
    v
}

// ─────────── the doors, platform-free (iOS twin in brackets) ───────────

/// A fresh identity, drawn by the core from its own health-tested sources (mt_generate_mnemonic).
pub fn generate() -> Option<Zeroizing<String>> {
    mt_mnemonic::generate_mnemonic().ok()
}

/// 24 words → master seed[64] (mt_mnemonic_to_master_seed).
pub fn master_seed(words: &str) -> Option<Zeroizing<[u8; 64]>> {
    mnemonic_to_master_seed(words).ok().map(Zeroizing::new)
}

/// 24 words → entropy[32] (mt_mnemonic_to_entropy).
pub fn entropy(words: &str) -> Option<Zeroizing<[u8; 32]>> {
    mnemonic_to_entropy(words).ok().map(Zeroizing::new)
}

/// master seed + role → 32-byte role seed (mt_mldsa_seed_for_role).
pub fn role_seed(master: &[u8; 64], role: &[u8]) -> Zeroizing<[u8; 32]> {
    Zeroizing::new(mldsa_seed_for_role(master, role))
}

/// 24 words → the person's ML-DSA-65 signing keys, pk[1952] ‖ sk[4032] (mt_seed_keys).
pub fn seed_keys(words: &str) -> Option<Zeroizing<Vec<u8>>> {
    let master = master_seed(words)?;
    seed_keys_from_master(&master)
}

/// The same keys from a master seed already stretched (mt_seed_keys_from_master): the app stretches the phrase once and
/// derives everything from the result -- the stretch of 2^20 rounds exists to slow a guesser, not the person.
pub fn seed_keys_from_master(master: &[u8; 64]) -> Option<Zeroizing<Vec<u8>>> {
    let sign_seed = role_seed(master, domain::ACCOUNT_KEY);
    let (pk, sk) = keypair_from_seed(&sign_seed).ok()?;
    let mut out = Zeroizing::new(Vec::with_capacity(PUBKEY_LEN + SECKEY_LEN));
    out.extend_from_slice(pk.as_bytes());
    out.extend_from_slice(sk.as_bytes());
    Some(out)
}

/// Bytes drawn by the core from its own sources — a copy's salt (mt_random_fast); None when the core refuses.
pub fn random(len: usize) -> Option<Vec<u8>> {
    if len == 0 || len > 4096 { return None; }
    let mut out = vec![0u8; len];
    mt_mnemonic::random_fast(&mut out).ok()?;
    Some(out)
}

/// entropy[32] → history_key[32]: HKDF-SHA-256(salt=0×32, ikm=entropy, info="mt-history-key") (mt_history_key).
pub fn history_key(entropy: &[u8; 32]) -> Zeroizing<[u8; 32]> {
    let prk = Zeroizing::new(mt_mnemonic::hmac_sha256(&[0u8; 32], entropy));
    let okm = Zeroizing::new(mt_mnemonic::hkdf_expand(&prk[..], domain::MSG_HISTORY_KEY, 32));
    let mut out = Zeroizing::new([0u8; 32]);
    out.copy_from_slice(&okm);
    out
}

/// Which conversation a sealed block belongs to, readable only under the history key (mt_archive_peek_conv).
pub fn archive_peek_conv(hk: &[u8; 32], owner: &[u8; 32], sealed: &[u8]) -> Option<[u8; 32]> {
    mt_messenger_e2e::archive::peek_conv(hk, owner, sealed)
}

/// A sealed block filed as-stored into the chat's log: 1 appended, 0 already held, <0 refused (mt_archive_ingest).
pub fn archive_ingest(base: &str, chat: &str, hk: &[u8; 32], owner: &[u8; 32], sealed: &[u8]) -> i32 {
    let store = match mt_messenger_e2e::archive::ArchiveStore::open(base) {
        Ok(s) => s,
        Err(_) => return -5, // MT_ERR_IO
    };
    match store.ingest_block(chat, hk, owner, sealed) {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(e) if e.kind() == std::io::ErrorKind::InvalidData => -4, // MT_ERR_DECODE
        Err(_) => -5,
    }
}

/// A card's key: ML-KEM-768 KeyGen from a 64-byte seed, pk[1184] ‖ sk[2400] (mt_mlkem_keypair_from_seed).
pub fn mlkem_keypair(seed: &[u8; 64]) -> Option<Zeroizing<Vec<u8>>> {
    let (pk, sk) = mt_crypto::keypair_from_seed_mlkem(seed).ok()?;
    let mut out = Zeroizing::new(Vec::with_capacity(1184 + 2400));
    out.extend_from_slice(pk.as_bytes());
    out.extend_from_slice(sk.as_bytes());
    Some(out)
}

/// A blob for the node: nonce ‖ Seal(key, nonce, input, AD=mt-media) (mt_e2e_seal_blob).
pub fn seal_blob(key: &[u8; 32], nonce: &[u8; 12], input: &[u8]) -> Vec<u8> {
    mt_messenger_e2e::media::seal_blob(key, nonce, input)
}

/// The blob opened, or None when the key is not its key (mt_e2e_open_blob).
pub fn open_blob(key: &[u8; 32], sealed: &[u8]) -> Option<Vec<u8>> {
    mt_messenger_e2e::media::open_blob(key, sealed)
}

/// A card's key opens what was encapsulated to it: sk[2400] + ct[1088] -> ss[32], implicit rejection
/// (mt_mlkem_decaps). A foreign ciphertext yields garbage, never a refusal — the proof is the seal.
pub fn mlkem_decaps(sk: &[u8], ct: &[u8]) -> Option<Zeroizing<[u8; 32]>> {
    let sk = mt_crypto::MlkemSecretKey::from_slice(sk)?;
    let ct = mt_crypto::MlkemCiphertext::from_slice(ct)?;
    let ss = mt_crypto::mlkem_decapsulate(&sk, &ct).ok()?;
    let mut out = Zeroizing::new([0u8; 32]);
    out.copy_from_slice(ss.as_bytes());
    Some(out)
}

/// The scanner's side: one encapsulation to a card's key, ct[1088] ‖ ss[32] (mt_mlkem_encaps).
pub fn mlkem_encaps(pk: &[u8]) -> Option<Zeroizing<Vec<u8>>> {
    let pk = mt_crypto::MlkemPublicKey::from_slice(pk)?;
    let (ct, ss) = mt_crypto::mlkem_encapsulate(&pk).ok()?;
    let mut out = Zeroizing::new(Vec::with_capacity(1088 + 32));
    out.extend_from_slice(ct.as_bytes());
    out.extend_from_slice(ss.as_bytes());
    Some(out)
}

/// The secret of a first letter, every later tag of the correspondence stands on it (mt_name_first_secret).
pub fn first_secret(ss: &[u8], root: &[u8], ct: &[u8]) -> Zeroizing<[u8; 32]> {
    Zeroizing::new(mt_names::first_secret(ss, root, ct))
}

// ─────────── Montana Business: the doors of mt-business (iOS twins mt_biz_* in mt-bindings/src/ffi_business.rs) ───────────

use mt_business::BizError;
use std::cell::Cell;

thread_local! {
    /// The refusal of the last Business door called on this thread, by the C door's code; 0 after a door that answered.
    static BIZ_LAST: Cell<i32> = const { Cell::new(0) };
}

// The C doors' codes (mt-bindings: MT_ERR_NULL_PTR and MT_ERR_SIGN_FAILED in lib.rs, MT_ERR_BIZ_* in ffi_business.rs),
// held here because this library links the core's crates, not mt-bindings; `tests` prove every one equal to its twin.
pub const MT_ERR_NULL_PTR: i32 = -1;
pub const MT_ERR_SIGN_FAILED: i32 = -7;
pub const MT_ERR_BIZ_DENIED: i32 = -40;
pub const MT_ERR_BIZ_ATTEST: i32 = -41;
pub const MT_ERR_BIZ_FRAME: i32 = -42;
pub const MT_ERR_BIZ_COMMAND: i32 = -43;

/// The C door's code of a refusal (ffi_business.rs `code`): a key the core will not take is the signing's failure.
pub fn biz_code(e: &BizError) -> i32 {
    match e {
        BizError::Denied(_) => MT_ERR_BIZ_DENIED,
        BizError::Attest(_) => MT_ERR_BIZ_ATTEST,
        BizError::Frame(_) => MT_ERR_BIZ_FRAME,
        BizError::Command(_) => MT_ERR_BIZ_COMMAND,
        BizError::Key(_) => MT_ERR_SIGN_FAILED,
    }
}

fn biz_set_last(code: i32) {
    BIZ_LAST.with(|c| c.set(code));
}

/// The refusal of the last Business door on this thread (MtBusiness.nativeLastError).
pub fn biz_last_error() -> i32 {
    BIZ_LAST.with(Cell::get)
}

/// An answer on its way out: its refusal kept for nativeLastError, its value handed on.
fn biz_note<T>(r: Result<T, BizError>) -> Option<T> {
    match r {
        Ok(v) => {
            biz_set_last(0);
            Some(v)
        },
        Err(e) => {
            biz_set_last(biz_code(&e));
            None
        },
    }
}

/// The author's next record, as a chain of one (mt_biz_author).
pub fn biz_author(sk: &[u8], pk: &[u8], roster: &[u8], hr: &[u8], command: &str, at_ms: u64) -> Option<Vec<u8>> {
    biz_note(mt_business::author(sk, pk, roster, hr, command, at_ms))
}

/// Two chains joined by id, in the order (at_ms, id) (mt_biz_merge); how many came new is the iOS door's out_added.
pub fn biz_merge(have: &[u8], incoming: &[u8]) -> Option<(Vec<u8>, usize)> {
    biz_note(mt_business::merge(have, incoming))
}

/// The view JSON for the viewer at now_ms (mt_biz_view).
pub fn biz_view(roster: &[u8], hr: &[u8], viewer: &[u8], now_ms: u64) -> Option<String> {
    biz_note(mt_business::view(roster, hr, viewer, now_ms))
}

/// The records of one lane of H (mt_biz_slice).
pub fn biz_slice(hr: &[u8], lane: &[u8]) -> Option<Vec<u8>> {
    biz_note(mt_business::slice(hr, lane))
}

/// The service's confirmation, checked under the pinned key, as JSON (mt_biz_attest_open).
pub fn biz_attest_open(attest: &[u8]) -> Option<String> {
    biz_note(mt_business::attest_open(attest))
}

/// The pinned public key of the service (mt_biz_service_key).
pub fn biz_service_key() -> Option<[u8; PUBKEY_LEN]> {
    biz_note(mt_business::service_key())
}

/// A member's id from the key they joined with (mt_biz_member_id).
pub fn biz_member_id(pk: &[u8]) -> Option<[u8; 32]> {
    biz_note(mt_business::member_id(pk))
}

/// The heads of a stream (mt_biz_heads): per lane, the highest place held and the digest of the ids at or below it.
pub fn biz_heads(stream: &[u8]) -> Option<Vec<u8>> {
    biz_note(mt_business::heads(stream))
}

/// What the holder of `heads` lacks of `stream` (mt_biz_after).
pub fn biz_after(stream: &[u8], heads: &[u8]) -> Option<Vec<u8>> {
    biz_note(mt_business::after(stream, heads))
}

/// A stream kept to one organization, its genesis id 32 bytes (mt_biz_keep): nothing of another is merged.
pub fn biz_keep(org: &[u8], stream: &[u8]) -> Option<Vec<u8>> {
    biz_note(mt_business::keep(org, stream))
}

/// An invitation's id from its secret (mt_biz_invite_id): the id the view names the invitation by, the core's one door for it.
pub fn biz_invite_id(secret: &[u8]) -> Option<[u8; 32]> {
    biz_note(mt_business::invite_id(secret))
}

/// A moment handed in from Java (signed): a negative one is a bad field of the command, as the core names it.
pub fn biz_moment(ms: i64) -> Option<u64> {
    let m = u64::try_from(ms).ok();
    if m.is_none() {
        biz_set_last(biz_code(&BizError::Command("at_ms")));
    }
    m
}

/// An argument Java could not hand over (a null array or string): the C doors' null pointer.
pub fn biz_no_argument() {
    biz_set_last(MT_ERR_NULL_PTR);
}

// ─────────── JNI: class quest.montana.app.MtBindings ───────────

#[cfg(target_os = "android")]
mod jni_doors {
    use super::*;
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::{jbyteArray, jint, jstring};
    use jni::JNIEnv;

    fn words(env: &mut JNIEnv, s: &JString) -> Option<Zeroizing<String>> {
        env.get_string(s).ok().map(|j| Zeroizing::new(String::from(j)))
    }
    fn bytes(env: &mut JNIEnv, b: &[u8]) -> jbyteArray {
        env.byte_array_from_slice(b).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeAbiVersion(_e: JNIEnv, _c: JClass) -> jint {
        ABI_VERSION as jint
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeGenerateMnemonic(mut env: JNIEnv, _c: JClass) -> jstring {
        match generate() {
            Some(m) => env.new_string(m.as_str()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeMnemonicToMasterSeed<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, m: JString<'l>,
    ) -> jbyteArray {
        match words(&mut env, &m).and_then(|w| master_seed(&w)) {
            Some(s) => bytes(&mut env, &s[..]),
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeMnemonicToEntropy<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, m: JString<'l>,
    ) -> jbyteArray {
        match words(&mut env, &m).and_then(|w| entropy(&w)) {
            Some(e) => bytes(&mut env, &e[..]),
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeSeedKeys<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, m: JString<'l>,
    ) -> jbyteArray {
        match words(&mut env, &m).and_then(|w| seed_keys(&w)) {
            Some(k) => bytes(&mut env, &k[..]),
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeSeedKeysFromMaster<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, master: JByteArray<'l>,
    ) -> jbyteArray {
        let m = match env.convert_byte_array(&master) {
            Ok(v) if v.len() == 64 => Zeroizing::new(v),
            _ => return std::ptr::null_mut(),
        };
        let mut arr = Zeroizing::new([0u8; 64]);
        arr.copy_from_slice(&m);
        match seed_keys_from_master(&arr) {
            Some(k) => bytes(&mut env, &k[..]),
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeRoleSeed<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, master: JByteArray<'l>, role: JString<'l>,
    ) -> jbyteArray {
        let m = match env.convert_byte_array(&master) {
            Ok(v) if v.len() == 64 => Zeroizing::new(v),
            _ => return std::ptr::null_mut(),
        };
        let role: String = match env.get_string(&role) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };
        let mut arr = Zeroizing::new([0u8; 64]);
        arr.copy_from_slice(&m);
        let seed = role_seed(&arr, role.as_bytes());
        bytes(&mut env, &seed[..])
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeRandom(mut env: JNIEnv, _c: JClass, len: jint) -> jbyteArray {
        match random(len.max(0) as usize) {
            Some(b) => bytes(&mut env, &b),
            None => std::ptr::null_mut(),
        }
    }

    fn key32(env: &mut JNIEnv, b: &JByteArray) -> Option<Zeroizing<[u8; 32]>> {
        let v = Zeroizing::new(env.convert_byte_array(b).ok()?);
        if v.len() != 32 { return None; }
        let mut k = Zeroizing::new([0u8; 32]);
        k.copy_from_slice(&v);
        Some(k)
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeMlkemKeypair<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, seed: JByteArray<'l>,
    ) -> jbyteArray {
        let v = match env.convert_byte_array(&seed) { Ok(v) if v.len() == 64 => Zeroizing::new(v), _ => return std::ptr::null_mut() };
        let mut s = Zeroizing::new([0u8; 64]);
        s.copy_from_slice(&v);
        match mlkem_keypair(&s) { Some(k) => bytes(&mut env, &k[..]), None => std::ptr::null_mut() }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeSealBlob<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, key: JByteArray<'l>, nonce: JByteArray<'l>, input: JByteArray<'l>,
    ) -> jbyteArray {
        let (Some(k), Ok(n), Ok(i)) = (key32(&mut env, &key), env.convert_byte_array(&nonce), env.convert_byte_array(&input)) else {
            return std::ptr::null_mut();
        };
        if n.len() != 12 { return std::ptr::null_mut(); }
        let mut nn = [0u8; 12];
        nn.copy_from_slice(&n);
        let out = seal_blob(&k, &nn, &i);
        bytes(&mut env, &out)
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeOpenBlob<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, key: JByteArray<'l>, sealed: JByteArray<'l>,
    ) -> jbyteArray {
        let (Some(k), Ok(s)) = (key32(&mut env, &key), env.convert_byte_array(&sealed)) else { return std::ptr::null_mut() };
        match open_blob(&k, &s) { Some(p) => bytes(&mut env, &p), None => std::ptr::null_mut() }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeMlkemDecaps<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, sk: JByteArray<'l>, ct: JByteArray<'l>,
    ) -> jbyteArray {
        let (Ok(s), Ok(c)) = (env.convert_byte_array(&sk), env.convert_byte_array(&ct)) else { return std::ptr::null_mut() };
        let s = Zeroizing::new(s);
        match mlkem_decaps(&s, &c) { Some(ss) => bytes(&mut env, &ss[..]), None => std::ptr::null_mut() }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeMlkemEncaps<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, pk: JByteArray<'l>,
    ) -> jbyteArray {
        let Ok(p) = env.convert_byte_array(&pk) else { return std::ptr::null_mut() };
        match mlkem_encaps(&p) { Some(o) => bytes(&mut env, &o[..]), None => std::ptr::null_mut() }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeFirstSecret<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, ss: JByteArray<'l>, root: JByteArray<'l>, ct: JByteArray<'l>,
    ) -> jbyteArray {
        let (Ok(s), Ok(r), Ok(c)) = (env.convert_byte_array(&ss), env.convert_byte_array(&root), env.convert_byte_array(&ct)) else {
            return std::ptr::null_mut();
        };
        let s = Zeroizing::new(s);
        let out = first_secret(&s, &r, &c);
        bytes(&mut env, &out[..])
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeHistoryKey<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, ent: JByteArray<'l>,
    ) -> jbyteArray {
        match key32(&mut env, &ent) {
            Some(e) => { let hk = history_key(&e); bytes(&mut env, &hk[..]) }
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeArchivePeekConv<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, hk: JByteArray<'l>, owner: JByteArray<'l>, sealed: JByteArray<'l>,
    ) -> jbyteArray {
        let (Some(h), Some(o), Ok(s)) = (key32(&mut env, &hk), key32(&mut env, &owner), env.convert_byte_array(&sealed)) else {
            return std::ptr::null_mut();
        };
        match archive_peek_conv(&h, &o, &s) {
            Some(conv) => bytes(&mut env, &conv),
            None => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBindings_nativeArchiveIngest<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, base: JString<'l>, chat: JString<'l>,
        hk: JByteArray<'l>, owner: JByteArray<'l>, sealed: JByteArray<'l>,
    ) -> jint {
        let base: String = match env.get_string(&base) { Ok(s) => s.into(), Err(_) => return -3 };
        let chat: String = match env.get_string(&chat) { Ok(s) => s.into(), Err(_) => return -3 };
        let (Some(h), Some(o), Ok(s)) = (key32(&mut env, &hk), key32(&mut env, &owner), env.convert_byte_array(&sealed)) else {
            return -1;
        };
        archive_ingest(&base, &chat, &h, &o, &s)
    }
}

// ─────────── JNI: class quest.montana.app.MtBusiness ───────────

#[cfg(target_os = "android")]
mod jni_business {
    use super::*;
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::{jbyteArray, jint, jlong, jstring};
    use jni::JNIEnv;

    fn arg(env: &mut JNIEnv, b: &JByteArray) -> Option<Vec<u8>> {
        let v = env.convert_byte_array(b).ok();
        if v.is_none() {
            biz_no_argument();
        }
        v
    }
    fn text(env: &mut JNIEnv, s: &JString) -> Option<String> {
        let v = env.get_string(s).ok().map(String::from);
        if v.is_none() {
            biz_no_argument();
        }
        v
    }
    fn out_bytes(env: &mut JNIEnv, b: Option<&[u8]>) -> jbyteArray {
        match b.map(|b| env.byte_array_from_slice(b)) {
            Some(Ok(a)) => a.into_raw(),
            _ => std::ptr::null_mut(),
        }
    }
    fn out_text(env: &mut JNIEnv, t: Option<String>) -> jstring {
        match t.map(|t| env.new_string(t)) {
            Some(Ok(s)) => s.into_raw(),
            _ => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBusiness_nativeAuthor<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, sk: JByteArray<'l>, pk: JByteArray<'l>, roster: JByteArray<'l>,
        hr: JByteArray<'l>, command: JString<'l>, at_ms: jlong,
    ) -> jbyteArray {
        let Some(s) = arg(&mut env, &sk).map(Zeroizing::new) else { return std::ptr::null_mut() };
        let (Some(p), Some(r), Some(h), Some(cmd), Some(at)) =
            (arg(&mut env, &pk), arg(&mut env, &roster), arg(&mut env, &hr), text(&mut env, &command), biz_moment(at_ms))
        else {
            return std::ptr::null_mut();
        };
        let rec = biz_author(&s, &p, &r, &h, &cmd, at);
        out_bytes(&mut env, rec.as_deref())
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBusiness_nativeMerge<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, have: JByteArray<'l>, incoming: JByteArray<'l>,
    ) -> jbyteArray {
        let (Some(a), Some(b)) = (arg(&mut env, &have), arg(&mut env, &incoming)) else { return std::ptr::null_mut() };
        let merged = biz_merge(&a, &b);
        out_bytes(&mut env, merged.as_ref().map(|(m, _)| m.as_slice()))
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBusiness_nativeView<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, roster: JByteArray<'l>, hr: JByteArray<'l>, viewer: JByteArray<'l>, now_ms: jlong,
    ) -> jstring {
        let (Some(r), Some(h), Some(v), Some(now)) =
            (arg(&mut env, &roster), arg(&mut env, &hr), arg(&mut env, &viewer), biz_moment(now_ms))
        else {
            return std::ptr::null_mut();
        };
        let view = biz_view(&r, &h, &v, now);
        out_text(&mut env, view)
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBusiness_nativeSlice<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, hr: JByteArray<'l>, lane: JByteArray<'l>,
    ) -> jbyteArray {
        let (Some(h), Some(l)) = (arg(&mut env, &hr), arg(&mut env, &lane)) else { return std::ptr::null_mut() };
        let part = biz_slice(&h, &l);
        out_bytes(&mut env, part.as_deref())
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBusiness_nativeAttestOpen<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, attest: JByteArray<'l>,
    ) -> jstring {
        let Some(a) = arg(&mut env, &attest) else { return std::ptr::null_mut() };
        let opened = biz_attest_open(&a);
        out_text(&mut env, opened)
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBusiness_nativeServiceKey(mut env: JNIEnv, _c: JClass) -> jbyteArray {
        let key = biz_service_key();
        out_bytes(&mut env, key.as_ref().map(|k| k.as_slice()))
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBusiness_nativeMemberId<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, pk: JByteArray<'l>,
    ) -> jbyteArray {
        let Some(p) = arg(&mut env, &pk) else { return std::ptr::null_mut() };
        let id = biz_member_id(&p);
        out_bytes(&mut env, id.as_ref().map(|i| i.as_slice()))
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBusiness_nativeHeads<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, stream: JByteArray<'l>,
    ) -> jbyteArray {
        let Some(s) = arg(&mut env, &stream) else { return std::ptr::null_mut() };
        let heads = biz_heads(&s);
        out_bytes(&mut env, heads.as_deref())
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBusiness_nativeAfter<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, stream: JByteArray<'l>, heads: JByteArray<'l>,
    ) -> jbyteArray {
        let (Some(s), Some(h)) = (arg(&mut env, &stream), arg(&mut env, &heads)) else { return std::ptr::null_mut() };
        let lacked = biz_after(&s, &h);
        out_bytes(&mut env, lacked.as_deref())
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBusiness_nativeKeep<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, org: JByteArray<'l>, stream: JByteArray<'l>,
    ) -> jbyteArray {
        let (Some(o), Some(s)) = (arg(&mut env, &org), arg(&mut env, &stream)) else { return std::ptr::null_mut() };
        let kept = biz_keep(&o, &s);
        out_bytes(&mut env, kept.as_deref())
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBusiness_nativeInviteId<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, secret: JByteArray<'l>,
    ) -> jbyteArray {
        let Some(s) = arg(&mut env, &secret) else { return std::ptr::null_mut() };
        let id = biz_invite_id(&s);
        out_bytes(&mut env, id.as_ref().map(|i| i.as_slice()))
    }

    #[no_mangle]
    pub extern "system" fn Java_quest_montana_app_MtBusiness_nativeLastError(_e: JNIEnv, _c: JClass) -> jint {
        biz_last_error()
    }
}

// ─────────── proof: every door answers as the iOS door (mt-bindings C ABI) ───────────

#[cfg(test)]
mod tests {
    use super::*;
    use mt_bindings::ffi_c;
    use std::ffi::CString;

    fn some_words() -> String { generate().expect("the core draws a phrase").to_string() }

    #[test]
    fn mlkem_keypair_matches_ios() {
        let seed: [u8; 64] = core::array::from_fn(|i| (i * 7 + 3) as u8);
        let mut pk = vec![0u8; 1184];
        let mut sk = vec![0u8; 2400];
        assert_eq!(unsafe { ffi_c::mt_mlkem_keypair_from_seed(seed.as_ptr(), pk.as_mut_ptr(), sk.as_mut_ptr()) }, 0);
        let ours = mlkem_keypair(&seed).unwrap();
        assert_eq!(&ours[..1184], &pk[..]);
        assert_eq!(&ours[1184..], &sk[..]);
    }

    #[test]
    fn blob_seal_matches_ios_and_opens() {
        let key = [9u8; 32];
        let nonce = [4u8; 12];
        let msg = b"montana card payload";
        let mut out: *mut u8 = std::ptr::null_mut();
        let mut len = 0usize;
        assert_eq!(unsafe { mt_bindings::ffi_e2e::mt_e2e_seal_blob(key.as_ptr(), nonce.as_ptr(), msg.as_ptr(), msg.len(), &mut out, &mut len) }, 0);
        let theirs = unsafe { std::slice::from_raw_parts(out, len) }.to_vec();
        unsafe { mt_bindings::ffi_e2e::mt_e2e_free(out, len) };
        let ours = seal_blob(&key, &nonce, msg);
        assert_eq!(ours, theirs);
        assert_eq!(open_blob(&key, &ours).as_deref(), Some(&msg[..]));
        assert!(open_blob(&[1u8; 32], &ours).is_none());
    }

    #[test]
    fn first_contact_matches_ios() {
        // The scanner's side through the iOS doors: encapsulate to the card key, derive the first secret.
        let seed: [u8; 64] = core::array::from_fn(|i| (i * 5 + 1) as u8);
        let kp = mlkem_keypair(&seed).unwrap();
        let (pk, sk) = (&kp[..1184], &kp[1184..]);
        let mut ct = vec![0u8; 1088];
        let mut ss = [0u8; 32];
        assert_eq!(unsafe { ffi_c::mt_mlkem_encaps(pk.as_ptr(), ct.as_mut_ptr(), ss.as_mut_ptr()) }, 0);
        let mut theirs = [0u8; 32];
        assert_eq!(unsafe { mt_bindings::ffi_names::mt_name_first_secret(ss.as_ptr(), 32, pk.as_ptr(), 1184, ct.as_ptr(), 1088, theirs.as_mut_ptr()) }, 0);
        // The holder's side through ours: the same shared secret, the same first secret.
        let mine = mlkem_decaps(sk, &ct).unwrap();
        assert_eq!(&mine[..], &ss[..]);
        let mut ios_ss = [0u8; 32];
        assert_eq!(unsafe { ffi_c::mt_mlkem_decaps(sk.as_ptr(), ct.as_ptr(), ios_ss.as_mut_ptr()) }, 0);
        assert_eq!(ios_ss, ss);
        assert_eq!(&first_secret(&mine[..], pk, &ct)[..], &theirs[..]);
    }

    #[test]
    fn encaps_opens_under_ios_decaps() {
        // Ours encapsulates (the scanner is Android), the iOS door decapsulates (the card's owner is an iPhone).
        let seed: [u8; 64] = core::array::from_fn(|i| (i * 11 + 2) as u8);
        let kp = mlkem_keypair(&seed).unwrap();
        let (pk, sk) = (&kp[..1184], &kp[1184..]);
        let o = mlkem_encaps(pk).unwrap();
        let (ct, ss) = (&o[..1088], &o[1088..]);
        let mut theirs = [0u8; 32];
        assert_eq!(unsafe { ffi_c::mt_mlkem_decaps(sk.as_ptr(), ct.as_ptr(), theirs.as_mut_ptr()) }, 0);
        assert_eq!(&theirs[..], ss);
    }

    #[test]
    fn abi_version_is_the_cores() {
        assert_eq!(ABI_VERSION, ffi_c::mt_abi_version());
    }

    #[test]
    fn master_seed_and_entropy_match_ios() {
        for _ in 0..3 {
            let w = some_words();
            let c = CString::new(w.clone()).unwrap();
            let mut ms = [0u8; 64];
            let mut en = [0u8; 32];
            assert_eq!(unsafe { ffi_c::mt_mnemonic_to_master_seed(c.as_ptr(), ms.as_mut_ptr()) }, 0);
            assert_eq!(unsafe { ffi_c::mt_mnemonic_to_entropy(c.as_ptr(), en.as_mut_ptr()) }, 0);
            assert_eq!(&master_seed(&w).unwrap()[..], &ms[..]);
            assert_eq!(&entropy(&w).unwrap()[..], &en[..]);
        }
    }

    #[test]
    fn seed_keys_match_ios() {
        let w = some_words();
        let c = CString::new(w.clone()).unwrap();
        let mut pk = vec![0u8; PUBKEY_LEN];
        let mut sk = vec![0u8; SECKEY_LEN];
        assert_eq!(unsafe { ffi_c::mt_seed_keys(c.as_ptr(), pk.as_mut_ptr(), sk.as_mut_ptr()) }, 0);
        let ours = seed_keys(&w).unwrap();
        assert_eq!(&ours[..PUBKEY_LEN], &pk[..]);
        assert_eq!(&ours[PUBKEY_LEN..], &sk[..]);
    }

    // The keys from the stretched master seed are the phrase's keys and the iOS door's, byte for byte: a door that derived
    // with another role, or from the entropy without the stretch, differs here.
    #[test]
    fn seed_keys_from_master_match_ios() {
        let w = some_words();
        let master = master_seed(&w).unwrap();
        let mut pk = vec![0u8; PUBKEY_LEN];
        let mut sk = vec![0u8; SECKEY_LEN];
        assert_eq!(unsafe { ffi_c::mt_seed_keys_from_master(master.as_ptr(), pk.as_mut_ptr(), sk.as_mut_ptr()) }, 0);
        let ours = seed_keys_from_master(&master).unwrap();
        assert_eq!(&ours[..PUBKEY_LEN], &pk[..]);
        assert_eq!(&ours[PUBKEY_LEN..], &sk[..]);
        assert_eq!(&ours[..], &seed_keys(&w).unwrap()[..]);
    }

    #[test]
    fn owner_role_seed_matches_ios() {
        let w = some_words();
        let master = master_seed(&w).unwrap();
        let role = b"mt-owner-key";
        let mut out = [0u8; 32];
        assert_eq!(unsafe { ffi_c::mt_mldsa_seed_for_role(master.as_ptr(), role.as_ptr(), role.len(), out.as_mut_ptr()) }, 0);
        assert_eq!(&role_seed(&master, role)[..], &out[..]);
    }

    #[test]
    fn random_is_the_cores() {
        let a = random(32).unwrap();
        let b = random(32).unwrap();
        assert_eq!(a.len(), 32);
        assert_ne!(a, b);
        assert!(random(0).is_none());
        let mut out = [0u8; 32];
        assert_eq!(unsafe { ffi_c::mt_random_fast(out.as_mut_ptr(), 32) }, 0);
    }

    #[test]
    fn history_key_matches_ios() {
        // iOS E2EVault.historyKeyKAT: the frozen vector on 0x55 × 32
        let k = history_key(&[0x55; 32]);
        let hexed: String = k.iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(hexed, "e6a7dc51003770589d9f731c1231c1523be7348c7769383875dd34bd6c578def");
        let e = entropy(&some_words()).unwrap();
        let mut out = [0u8; 32];
        assert_eq!(unsafe { ffi_c::mt_history_key(e.as_ptr(), out.as_mut_ptr()) }, 0);
        assert_eq!(&history_key(&e)[..], &out[..]);
    }

    #[test]
    fn archive_doors_match_ios() {
        // a block this device seals, taken back by both doors into two stores: the same answers, the same files
        let hk = [7u8; 32];
        let owner = [9u8; 32];
        let conv = [3u8; 32];
        let tmp = std::env::temp_dir().join(format!("mt-jni-archive-{}", std::process::id()));
        let (a, b) = (tmp.join("a"), tmp.join("b"));
        use mt_messenger_e2e::archive::{seal_block, HistoryBlock, HistoryItem};
        let item = HistoryItem { conv_id: conv, dir: 1, send_time: 1_700_000_000, content: b"hello".to_vec() };
        let sealed = seal_block(&hk, &owner, &[1u8; 16], &HistoryBlock { block_seq: 1, items: vec![item] });
        assert_eq!(archive_peek_conv(&hk, &owner, &sealed), Some(conv));
        let mut out = [0u8; 32];
        assert_eq!(unsafe { ffi_c::mt_archive_peek_conv(hk.as_ptr(), owner.as_ptr(), sealed.as_ptr(), sealed.len(), out.as_mut_ptr()) }, 0);
        assert_eq!(out, conv);
        let bs = CString::new(b.to_str().unwrap()).unwrap();
        let chat = CString::new("c1").unwrap();
        let ios = unsafe { ffi_c::mt_archive_ingest(bs.as_ptr(), chat.as_ptr(), hk.as_ptr(), owner.as_ptr(), sealed.as_ptr(), sealed.len()) };
        let ours = archive_ingest(a.to_str().unwrap(), "c1", &hk, &owner, &sealed);
        assert_eq!((ours, ios), (1, 1));
        assert_eq!(archive_ingest(a.to_str().unwrap(), "c1", &hk, &owner, &sealed), 0); // held already: merged, not doubled
        assert!(archive_ingest(a.to_str().unwrap(), "c1", &[8u8; 32], &owner, &sealed) < 0); // another key: refused
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn a_wrong_phrase_is_refused() {
        let mut w: Vec<String> = some_words().split(' ').map(String::from).collect();
        w.swap(0, 1);
        let swapped = w.join(" ");
        // a swap almost always breaks the checksum; the core and this door must agree either way
        let c = CString::new(swapped.clone()).unwrap();
        let mut ms = [0u8; 64];
        let ios_ok = unsafe { ffi_c::mt_mnemonic_to_master_seed(c.as_ptr(), ms.as_mut_ptr()) } == 0;
        assert_eq!(master_seed(&swapped).is_some(), ios_ok);
        assert!(master_seed("not a phrase").is_none());
    }

    // ─── Montana Business: every door of this library against its iOS twin mt_biz_* (mt-bindings/src/ffi_business.rs) ───

    use mt_bindings::ffi_business as fb;

    /// A C door that writes into the caller's buffer, asked first with a small one: the length it names, then the bytes.
    fn c_out(mut f: impl FnMut(*mut u8, usize, *mut usize) -> i32) -> Result<Vec<u8>, i32> {
        let mut cap = 16usize;
        loop {
            let mut out = vec![0u8; cap];
            let mut len = 0usize;
            let rc = f(out.as_mut_ptr(), cap, &mut len as *mut usize);
            if rc == 0 {
                out.truncate(len);
                return Ok(out);
            }
            if rc == mt_bindings::MT_ERR_BUFFER_TOO_SMALL && len > cap {
                cap = len;
                continue;
            }
            return Err(rc);
        }
    }

    fn biz_keys(seed: u8) -> (Vec<u8>, Vec<u8>) {
        let (pk, sk) = mt_crypto::keypair_from_seed(&[seed; 32]).expect("the core makes a key");
        (pk.as_bytes().to_vec(), sk.as_bytes().to_vec())
    }

    fn hexed(b: &[u8]) -> String {
        b.iter().map(|x| format!("{x:02x}")).collect()
    }

    /// Ours and the C door on one command: the same record, or the same refusal.
    fn author_both(sk: &[u8], pk: &[u8], r: &[u8], h: &[u8], cmd: &str, at: u64) -> Result<Vec<u8>, i32> {
        let c = CString::new(cmd).unwrap();
        let theirs = c_out(|out, cap, len| unsafe {
            fb::mt_biz_author(sk.as_ptr(), pk.as_ptr(), r.as_ptr(), r.len(), h.as_ptr(), h.len(), c.as_ptr(), at, out, cap, len)
        });
        let ours = biz_author(sk, pk, r, h, cmd, at).ok_or(biz_last_error());
        assert_eq!(ours, theirs, "author {cmd}");
        ours
    }

    fn merge_both(a: &[u8], b: &[u8]) -> Vec<u8> {
        let mut added = 0usize;
        let theirs = c_out(|out, cap, len| unsafe { fb::mt_biz_merge(a.as_ptr(), a.len(), b.as_ptr(), b.len(), out, cap, len, &mut added) });
        let ours = biz_merge(a, b).ok_or(biz_last_error());
        assert_eq!(ours.clone().map(|(m, _)| m), theirs);
        assert_eq!(ours.clone().map(|(_, n)| n), Ok(added));
        ours.unwrap().0
    }

    fn view_both(r: &[u8], h: &[u8], viewer: &[u8], now: u64) -> String {
        let theirs = c_out(|out, cap, len| unsafe { fb::mt_biz_view(r.as_ptr(), r.len(), h.as_ptr(), h.len(), viewer.as_ptr(), now, out, cap, len) });
        let ours = biz_view(r, h, viewer, now).ok_or(biz_last_error());
        assert_eq!(ours.clone().map(String::into_bytes), theirs);
        ours.unwrap()
    }

    #[test]
    fn biz_refusal_codes_are_the_c_doors() {
        assert_eq!(MT_ERR_NULL_PTR, mt_bindings::MT_ERR_NULL_PTR);
        assert_eq!(MT_ERR_SIGN_FAILED, mt_bindings::MT_ERR_SIGN_FAILED);
        assert_eq!(MT_ERR_BIZ_DENIED, fb::MT_ERR_BIZ_DENIED);
        assert_eq!(MT_ERR_BIZ_ATTEST, fb::MT_ERR_BIZ_ATTEST);
        assert_eq!(MT_ERR_BIZ_FRAME, fb::MT_ERR_BIZ_FRAME);
        assert_eq!(MT_ERR_BIZ_COMMAND, fb::MT_ERR_BIZ_COMMAND);
    }

    /// An organization lived through both doors: founded, an invitation, a Join, a role, a salary; every record, merge,
    /// view and slice byte for byte the same, and every refusal the same code. The member ids and the invitation differ
    /// between the two people, so a door that swapped the author and the subject, or merged in arrival order, fails here.
    #[test]
    fn biz_doors_match_ios() {
        let (opk, osk) = biz_keys(7);
        let (epk, esk) = biz_keys(8);
        let at = 1_780_000_000_000u64;
        let gen = author_both(&osk, &opk, &[], &[], r#"{"kind":"genesis","name":"Atelier","owner_name":"Owner","owner_card":"card-o"}"#, at).unwrap();
        let r1 = merge_both(&[], &gen);
        assert_eq!(r1, gen);
        let secret = hexed(&[9u8; 32]);
        let invite = format!(r#"{{"kind":"invite","secret":"{secret}","role":3,"expires_ms":{}}}"#, at + 86_400_000);
        let inv = author_both(&osk, &opk, &r1, &[], &invite, at + 1000).unwrap();
        let r2 = merge_both(&r1, &inv);
        // the merge is the same whichever chain comes first
        assert_eq!(merge_both(&inv, &r1), r2);
        let join = format!(r#"{{"kind":"join","secret":"{secret}","name":"Employee","card":"card-e"}}"#);
        let j = author_both(&esk, &epk, &r2, &[], &join, at + 2000).unwrap();
        let r3 = merge_both(&r2, &j);

        let mut owner_id = [0u8; 32];
        let mut emp_id = [0u8; 32];
        assert_eq!(unsafe { fb::mt_biz_member_id(opk.as_ptr(), owner_id.as_mut_ptr()) }, 0);
        assert_eq!(unsafe { fb::mt_biz_member_id(epk.as_ptr(), emp_id.as_mut_ptr()) }, 0);
        assert_eq!(biz_member_id(&opk), Some(owner_id));
        assert_eq!(biz_member_id(&epk), Some(emp_id));
        assert_ne!(owner_id, emp_id);

        let role = format!(r#"{{"kind":"role","member":"{}","role":2}}"#, hexed(&emp_id));
        let rr = author_both(&osk, &opk, &r3, &[], &role, at + 3000).unwrap();
        let r4 = merge_both(&r3, &rr);
        let salary = format!(r#"{{"kind":"salary","member":"{}","coins":100,"period":1,"from_ms":{at}}}"#, hexed(&emp_id));
        let sal = author_both(&osk, &opk, &r4, &[], &salary, at + 4000).unwrap();
        let h1 = merge_both(&[], &sal);

        // the employee may not set a salary: the same refusal from both doors
        let mine = format!(r#"{{"kind":"salary","member":"{}","coins":5,"period":1,"from_ms":{at}}}"#, hexed(&emp_id));
        assert_eq!(author_both(&esk, &epk, &r4, &h1, &mine, at + 5000), Err(MT_ERR_BIZ_DENIED));
        assert_eq!(biz_last_error(), MT_ERR_BIZ_DENIED);
        // a command that is not JSON, a key that is not a key, a chain that is not a chain
        assert_eq!(author_both(&osk, &opk, &r4, &h1, "not json", at), Err(MT_ERR_BIZ_COMMAND));
        assert_eq!(biz_author(&osk[..100], &opk, &r4, &h1, r#"{"kind":"profile","name":"x"}"#, at), None);
        assert_eq!(biz_last_error(), MT_ERR_SIGN_FAILED);
        let mut added = 0usize;
        let garbage = [1u8, 2, 3];
        let c_rc = c_out(|out, cap, len| unsafe { fb::mt_biz_merge(garbage.as_ptr(), 3, r4.as_ptr(), r4.len(), out, cap, len, &mut added) });
        assert_eq!(biz_merge(&garbage, &r4).map(|(m, _)| m).ok_or(biz_last_error()), c_rc);
        assert_eq!(c_rc, Err(MT_ERR_BIZ_FRAME));

        // the views of both people, and the employee's lane of H
        let now = at + 3 * 86_400_000;
        let ov = view_both(&r4, &h1, &opk, now);
        let ev = view_both(&r4, &biz_slice(&h1, &emp_id).unwrap(), &epk, now);
        assert!(ov.contains(r#""role":"owner""#) && ev.contains(r#""role":"manager""#));
        assert!(ov.contains(r#""name":"Atelier""#));
        let theirs = c_out(|out, cap, len| unsafe { fb::mt_biz_slice(h1.as_ptr(), h1.len(), emp_id.as_ptr(), out, cap, len) });
        assert_eq!(biz_slice(&h1, &emp_id).ok_or(biz_last_error()), theirs);
        let none = c_out(|out, cap, len| unsafe { fb::mt_biz_slice(h1.as_ptr(), h1.len(), owner_id.as_ptr(), out, cap, len) });
        assert_eq!(biz_slice(&h1, &owner_id).ok_or(biz_last_error()), none);
        assert_eq!(none, Ok(Vec::new()));
    }

    /// An invitation's id from its secret is the C door's and the id the view names it by: two invitations of two secrets and
    /// two roles, each found in the owner's view under the id the door gives its own secret. Wrong implementations this rejects:
    /// a door that hashes without the core's domain or answers the secret itself (the view would not hold that id), one that
    /// mixes the two secrets (each id must stand beside its own role), one that takes a secret of another length (refused with
    /// the frame's code, as the C door).
    #[test]
    fn biz_invite_id_is_the_views() {
        let (opk, osk) = biz_keys(7);
        let at = 1_780_000_000_000u64;
        let gen = author_both(&osk, &opk, &[], &[], r#"{"kind":"genesis","name":"Atelier","owner_name":"Owner","owner_card":"card-o"}"#, at).unwrap();
        let mut r = merge_both(&[], &gen);
        let (a, b) = ([9u8; 32], [5u8; 32]);
        for (s, role, t) in [(a, 3, 1000u64), (b, 2, 2000u64)] {
            let cmd = format!(r#"{{"kind":"invite","secret":"{}","role":{role},"expires_ms":{}}}"#, hexed(&s), at + 86_400_000);
            let rec = author_both(&osk, &opk, &r, &[], &cmd, at + t).unwrap();
            r = merge_both(&r, &rec);
        }
        let v = view_both(&r, &[], &opk, at + 3000);
        for s in [a, b] {
            let mut theirs = [0u8; 32];
            assert_eq!(unsafe { fb::mt_biz_invite_id(s.as_ptr(), theirs.as_mut_ptr()) }, 0);
            assert_eq!(biz_invite_id(&s), Some(theirs));
        }
        let ia = hexed(&biz_invite_id(&a).unwrap());
        let ib = hexed(&biz_invite_id(&b).unwrap());
        assert_ne!(ia, ib);
        assert_ne!(ia, hexed(&a));
        assert!(v.contains(&format!(r#""id":"{ia}","role":"employee""#)), "{v}");
        assert!(v.contains(&format!(r#""id":"{ib}","role":"manager""#)), "{v}");
        assert_eq!(biz_invite_id(&a[..31]), None);
        assert_eq!(biz_last_error(), MT_ERR_BIZ_FRAME);
    }

    fn heads_both(s: &[u8]) -> Result<Vec<u8>, i32> {
        let theirs = c_out(|out, cap, len| unsafe { fb::mt_biz_heads(s.as_ptr(), s.len(), out, cap, len) });
        let ours = biz_heads(s).ok_or(biz_last_error());
        assert_eq!(ours, theirs, "heads");
        ours
    }
    fn after_both(s: &[u8], h: &[u8]) -> Result<Vec<u8>, i32> {
        let theirs = c_out(|out, cap, len| unsafe { fb::mt_biz_after(s.as_ptr(), s.len(), h.as_ptr(), h.len(), out, cap, len) });
        let ours = biz_after(s, h).ok_or(biz_last_error());
        assert_eq!(ours, theirs, "after");
        ours
    }
    fn keep_both(o: &[u8; 32], s: &[u8]) -> Result<Vec<u8>, i32> {
        let theirs = c_out(|out, cap, len| unsafe { fb::mt_biz_keep(o.as_ptr(), s.as_ptr(), s.len(), out, cap, len) });
        let ours = biz_keep(o, s).ok_or(biz_last_error());
        assert_eq!(ours, theirs, "keep");
        ours
    }

    /// Sync by the heads of lanes (mt-business 2f6e012): heads, after and keep through both doors on one organization -- the
    /// same heads, the same lacked records for the heads of an earlier roster, for no heads at all and for one's own heads,
    /// the same stream kept to its organization out of a stream mixed with another's, the same refusal of heads that are not
    /// heads. Wrong implementations these reject: a door that answers the whole stream whatever the heads (the earlier
    /// roster's heads must leave only what came after), one that keeps nothing or everything (another organization's
    /// genesis must go, this one's records must stay, and an organization the stream does not hold keeps nothing), one that
    /// maps a refusal to another code.
    #[test]
    fn biz_sync_doors_match_ios() {
        let (opk, osk) = biz_keys(7);
        let (epk, esk) = biz_keys(8);
        let (xpk, xsk) = biz_keys(9);
        let at = 1_780_000_000_000u64;
        let gen = author_both(&osk, &opk, &[], &[], r#"{"kind":"genesis","name":"Atelier","owner_name":"Owner","owner_card":"card-o"}"#, at).unwrap();
        let r1 = merge_both(&[], &gen);
        let secret = hexed(&[9u8; 32]);
        let invite = format!(r#"{{"kind":"invite","secret":"{secret}","role":3,"expires_ms":{}}}"#, at + 86_400_000);
        let r2 = merge_both(&r1, &author_both(&osk, &opk, &r1, &[], &invite, at + 1000).unwrap());
        let join = format!(r#"{{"kind":"join","secret":"{secret}","name":"Employee","card":"card-e"}}"#);
        let r3 = merge_both(&r2, &author_both(&esk, &epk, &r2, &[], &join, at + 2000).unwrap());

        let h1 = heads_both(&r1).unwrap();
        let h3 = heads_both(&r3).unwrap();
        assert_ne!(h1, h3);
        // the holder of the founding alone lacks the invitation and the join, and nothing more
        let lacked = after_both(&r3, &h1).unwrap();
        assert!(!lacked.is_empty() && lacked.len() < r3.len());
        assert_eq!(merge_both(&r1, &lacked), r3);
        // no heads at all (a count of zero): the whole stream; one's own heads: nothing
        assert_eq!(after_both(&r3, &[0, 0, 0, 0]).unwrap(), r3);
        assert_eq!(after_both(&r3, &h3).unwrap(), Vec::<u8>::new());
        // heads that are not heads: the same refusal from both doors
        assert_eq!(after_both(&r3, &[1, 2, 3]), Err(MT_ERR_BIZ_FRAME));

        // the organization's id is its genesis record's id, as the view names it
        let v = view_both(&r3, &[], &opk, at + 3000);
        let needle = r#""org":{"id":""#;
        let i = v.find(needle).unwrap() + needle.len();
        let org_hex = &v[i..i + 64];
        let org: [u8; 32] = core::array::from_fn(|k| u8::from_str_radix(&org_hex[2 * k..2 * k + 2], 16).unwrap());
        // a stream mixed with another organization's genesis: kept to this one, the other's record gone
        let other = author_both(&xsk, &xpk, &[], &[], r#"{"kind":"genesis","name":"Elsewhere","owner_name":"X","owner_card":"card-x"}"#, at + 500).unwrap();
        let mixed = merge_both(&r3, &other);
        assert!(r3.len() < mixed.len());
        assert_eq!(keep_both(&org, &mixed).unwrap(), r3);
        // an organization the stream does not hold: nothing kept (zero is no such id -- a genesis carries a zero org field)
        assert_eq!(keep_both(&[5u8; 32], &r3).unwrap(), Vec::<u8>::new());
    }

    /// The service's key and a confirmation it signed: the same key, the same opened JSON (or the same refusal, once the
    /// pinned key is the production one and the test signer's word no longer opens).
    #[test]
    fn biz_attest_matches_ios() {
        let mut key = vec![0u8; PUBKEY_LEN];
        assert_eq!(unsafe { fb::mt_biz_service_key(key.as_mut_ptr()) }, 0);
        assert_eq!(biz_service_key().map(|k| k.to_vec()), Some(key));
        let (_, sk) = mt_business::attest::test_service_key().unwrap();
        let (epk, _) = biz_keys(8);
        let a = mt_business::attest::Attest {
            channel: mt_business::attest::CHANNEL_BOT,
            at_ms: 1_780_000_000_000,
            subject: mt_business::fold::subject_of(&mt_crypto::PublicKey::from_slice(&epk).unwrap()),
            e164: "+33612345678".into(),
        }
        .seal(&sk)
        .unwrap();
        let theirs = c_out(|out, cap, len| unsafe { fb::mt_biz_attest_open(a.as_ptr(), a.len(), out, cap, len) });
        assert_eq!(biz_attest_open(&a).map(String::into_bytes).ok_or(biz_last_error()), theirs);
        // one byte changed in the signature: refused by both, with one code
        let mut bad = a.clone();
        let last = bad.len() - 1;
        bad[last] ^= 1;
        let theirs = c_out(|out, cap, len| unsafe { fb::mt_biz_attest_open(bad.as_ptr(), bad.len(), out, cap, len) });
        assert_eq!(theirs, Err(MT_ERR_BIZ_ATTEST));
        assert_eq!(biz_attest_open(&bad).map(String::into_bytes).ok_or(biz_last_error()), theirs);
    }
}
