//! THE PAGE'S DOOR TO THE CORE (MT Business's mini app, 07.10.2026): the calls the app makes through ffi_c and ffi_e2e, by the
//! same core functions, for the page's WebAssembly. A secret crosses as a role's seed, never as an expanded key: every
//! signature and every decapsulation is born from its seed inside one call, so the expanded-key decode -- the one place the
//! RustCrypto hand trusts its input -- only ever reads bytes made a line before it.

use mt_codec::domain;
use mt_crypto::{
    keypair_from_seed, keypair_from_seed_mlkem, mlkem_decapsulate, mlkem_encapsulate, open_from,
    seal_to, sign, verify, MlkemCiphertext, MlkemPublicKey, PublicKey, Signature,
};
use mt_messenger_e2e::media::{blob_id, open_blob, pad_len, seal_blob};
use mt_mnemonic::{
    hkdf_expand, hmac_sha256, mldsa_seed_for_role, mlkem_seed_for_role, mnemonic_to_entropy,
    pbkdf2_hmac_sha256, KDF_ITER,
};
use wasm_bindgen::prelude::*;
use zeroize::Zeroizing;

use crate::{MT_MASTER_SEED_LEN, MT_MLDSA_SEED_LEN, MT_MLKEM_SEED_LEN};

// The page's check of its own stretch (WebCrypto's PBKDF2) runs a handful of rounds against this one; a probe longer than
// this would be the whole stretch done twice on the page's main thread.
const PROBE_ROUNDS_MAX: u32 = 64;

fn refused(word: &str) -> JsValue {
    JsValue::from_str(word)
}

fn array<const N: usize>(bytes: &[u8], word: &str) -> Result<Zeroizing<[u8; N]>, JsValue> {
    if bytes.len() != N {
        return Err(refused(word));
    }
    let mut out = Zeroizing::new([0u8; N]);
    out.copy_from_slice(bytes);
    Ok(out)
}

#[wasm_bindgen]
pub fn mt_web_abi() -> u32 {
    crate::ABI_VERSION
}

// -- the words and the root ------------------------------------------------------------------------------------------------

#[wasm_bindgen]
pub fn mt_entropy_from_words(words: &str) -> Result<Vec<u8>, JsValue> {
    match mnemonic_to_entropy(words) {
        Ok(e) => Ok(e.to_vec()),
        Err(mt_mnemonic::MnemonicError::WordCount(_)) => Err(refused("mnemonic_word_count")),
        Err(mt_mnemonic::MnemonicError::UnknownWord(_)) => Err(refused("mnemonic_unknown_word")),
        Err(mt_mnemonic::MnemonicError::ChecksumMismatch) => Err(refused("mnemonic_checksum")),
    }
}

// THE BIRTH IS THE CORE'S (07.10.2026): the root of a person is drawn by mt_mnemonic::generate_mnemonic from its own judged
// sources -- on the page they run on the browser's clocks (mt-mnemonic clock.rs), under the same rule of three live sources; a
// browser whose clocks leave too few alive is refused, never lowered. The page receives the words to show the person once.
#[wasm_bindgen]
pub fn mt_birth_words() -> Result<String, JsValue> {
    match mt_mnemonic::generate_mnemonic() {
        Ok(words) => Ok(words.to_string()),
        Err(e) => Err(refused(&format!("entropy: {e}"))),
    }
}

// What the sources of this machine measured -- counts only, never a byte of a source (the measure of each: samples, distinct,
// the most repeated, alive), in the order of the mix: the system's generator, jitter, memory, quartz, scheduler, timer.
#[wasm_bindgen]
pub fn mt_source_report() -> String {
    mt_mnemonic::measure_sources()
        .iter()
        .map(|m| {
            format!(
                "{}/{}/{}/{}",
                m.samples,
                m.distinct,
                m.max_repeat,
                u8::from(m.alive)
            )
        })
        .collect::<Vec<_>>()
        .join(" ")
}

#[wasm_bindgen]
pub fn mt_seed_salt() -> Vec<u8> {
    domain::SEED.to_vec()
}

#[wasm_bindgen]
pub fn mt_seed_rounds() -> u32 {
    KDF_ITER
}

#[wasm_bindgen]
pub fn mt_seed_probe(entropy: &[u8], rounds: u32) -> Result<Vec<u8>, JsValue> {
    if rounds == 0 || PROBE_ROUNDS_MAX < rounds {
        return Err(refused("probe_rounds"));
    }
    let e = array::<32>(entropy, "entropy_length")?;
    Ok(pbkdf2_hmac_sha256(
        e.as_ref(),
        domain::SEED,
        rounds,
        MT_MASTER_SEED_LEN,
    ))
}

#[wasm_bindgen]
pub fn mt_role(name: &str) -> Result<Vec<u8>, JsValue> {
    let role: &[u8] = match name {
        "account_key" => domain::ACCOUNT_KEY,
        "app_encryption_key" => domain::APP_ENCRYPTION_KEY,
        _ => return Err(refused("role_unknown")),
    };
    Ok(role.to_vec())
}

#[wasm_bindgen]
pub fn mt_mldsa_seed_for_role(master: &[u8], role: &[u8]) -> Result<Vec<u8>, JsValue> {
    let m = array::<MT_MASTER_SEED_LEN>(master, "master_length")?;
    Ok(mldsa_seed_for_role(&m, role).to_vec())
}

#[wasm_bindgen]
pub fn mt_mlkem_seed_for_role(master: &[u8], role: &[u8]) -> Result<Vec<u8>, JsValue> {
    let m = array::<MT_MASTER_SEED_LEN>(master, "master_length")?;
    Ok(mlkem_seed_for_role(&m, role).to_vec())
}

// -- ML-DSA-65 by its seed ---------------------------------------------------------------------------------------------------

#[wasm_bindgen]
pub fn mt_mldsa_public(seed: &[u8]) -> Result<Vec<u8>, JsValue> {
    let s = array::<MT_MLDSA_SEED_LEN>(seed, "seed_length")?;
    let (pk, _sk) = keypair_from_seed(&s).map_err(|_| refused("keygen"))?;
    Ok(pk.as_bytes().to_vec())
}

#[wasm_bindgen]
pub fn mt_sign_with_seed(seed: &[u8], msg: &[u8]) -> Result<Vec<u8>, JsValue> {
    let s = array::<MT_MLDSA_SEED_LEN>(seed, "seed_length")?;
    let (_pk, sk) = keypair_from_seed(&s).map_err(|_| refused("keygen"))?;
    let sig = sign(&sk, msg).map_err(|_| refused("sign"))?;
    Ok(sig.as_bytes().to_vec())
}

#[wasm_bindgen]
pub fn mt_verify(pubkey: &[u8], msg: &[u8], sig: &[u8]) -> bool {
    match (PublicKey::from_slice(pubkey), Signature::from_slice(sig)) {
        (Some(pk), Some(s)) => verify(&pk, msg, &s),
        _ => false,
    }
}

// -- ML-KEM-768 by its seed ----------------------------------------------------------------------------------------------------

#[wasm_bindgen]
pub fn mt_mlkem_public(seed: &[u8]) -> Result<Vec<u8>, JsValue> {
    let s = array::<MT_MLKEM_SEED_LEN>(seed, "seed_length")?;
    let (pk, _sk) = keypair_from_seed_mlkem(&s).map_err(|_| refused("keygen"))?;
    Ok(pk.as_bytes().to_vec())
}

// ct (1088) followed by the shared secret (32), as mt_mlkem_encaps writes its two outputs.
#[wasm_bindgen]
pub fn mt_mlkem_encaps(pubkey: &[u8]) -> Result<Vec<u8>, JsValue> {
    let pk = MlkemPublicKey::from_slice(pubkey).ok_or_else(|| refused("pubkey_length"))?;
    let (ct, ss) = mlkem_encapsulate(&pk).map_err(|_| refused("encaps"))?;
    let mut out = ct.as_bytes().to_vec();
    out.extend_from_slice(ss.as_bytes());
    Ok(out)
}

#[wasm_bindgen]
pub fn mt_mlkem_decaps_with_seed(seed: &[u8], ct: &[u8]) -> Result<Vec<u8>, JsValue> {
    let s = array::<MT_MLKEM_SEED_LEN>(seed, "seed_length")?;
    let (_pk, sk) = keypair_from_seed_mlkem(&s).map_err(|_| refused("keygen"))?;
    let c = MlkemCiphertext::from_slice(ct).ok_or_else(|| refused("ct_length"))?;
    let ss = mlkem_decapsulate(&sk, &c).map_err(|_| refused("decaps"))?;
    Ok(ss.as_bytes().to_vec())
}

#[wasm_bindgen]
pub fn mt_seal_to(pubkey: &[u8], plain: &[u8]) -> Result<Vec<u8>, JsValue> {
    let pk = MlkemPublicKey::from_slice(pubkey).ok_or_else(|| refused("pubkey_length"))?;
    seal_to(&pk, plain).map_err(|_| refused("seal"))
}

#[wasm_bindgen]
pub fn mt_open_from_seed(seed: &[u8], sealed: &[u8]) -> Result<Vec<u8>, JsValue> {
    let s = array::<MT_MLKEM_SEED_LEN>(seed, "seed_length")?;
    let (_pk, sk) = keypair_from_seed_mlkem(&s).map_err(|_| refused("keygen"))?;
    open_from(&sk, sealed).map_err(|_| refused("open"))
}

// -- the blobs: a letter's envelope and a file's pieces -----------------------------------------------------------------------

#[wasm_bindgen]
pub fn mt_e2e_seal_blob(key: &[u8], nonce: &[u8], input: &[u8]) -> Result<Vec<u8>, JsValue> {
    let k = array::<32>(key, "key_length")?;
    let n = array::<12>(nonce, "nonce_length")?;
    Ok(seal_blob(&k, &n, input))
}

#[wasm_bindgen]
pub fn mt_e2e_open_blob(key: &[u8], sealed: &[u8]) -> Result<Vec<u8>, JsValue> {
    let k = array::<32>(key, "key_length")?;
    open_blob(&k, sealed).ok_or_else(|| refused("open"))
}

#[wasm_bindgen]
pub fn mt_e2e_blob_id(sealed: &[u8]) -> Vec<u8> {
    blob_id(sealed).to_vec()
}

#[wasm_bindgen]
pub fn mt_e2e_pad_len(n: usize) -> usize {
    pad_len(n)
}

// -- the keys a seed's entropy opens (the history, the media, a call) --------------------------------------------------------

fn from_entropy(entropy: &[u8], info: &[u8]) -> Result<Vec<u8>, JsValue> {
    let e = array::<32>(entropy, "entropy_length")?;
    // HKDF-Extract(salt = 0x00 * 32, ikm = entropy), then Expand to 32 -- mt_history_key's and mt_media_key's own steps.
    let prk = Zeroizing::new(hmac_sha256(&[0u8; 32], e.as_ref()));
    Ok(hkdf_expand(prk.as_ref(), info, 32))
}

#[wasm_bindgen]
pub fn mt_history_key(entropy: &[u8]) -> Result<Vec<u8>, JsValue> {
    from_entropy(entropy, domain::MSG_HISTORY_KEY)
}

#[wasm_bindgen]
pub fn mt_media_key(entropy: &[u8]) -> Result<Vec<u8>, JsValue> {
    from_entropy(entropy, domain::MSG_MEDIA_KEY)
}

// call key (32) followed by its SFrame key (32), as mt_e2e_call_key writes them.
#[wasm_bindgen]
pub fn mt_e2e_call_key(call_seed: &[u8]) -> Result<Vec<u8>, JsValue> {
    let s = array::<32>(call_seed, "seed_length")?;
    let ck = mt_messenger_e2e::call::call_key(&s);
    let mut out = ck.to_vec();
    out.extend_from_slice(&mt_messenger_e2e::call::sframe_key(&ck));
    Ok(out)
}

// -- the canon's hash, the meeting and the core's randomness ----------------------------------------------------------------

#[wasm_bindgen]
pub fn mt_sha256(bytes: &[u8]) -> Vec<u8> {
    mt_mnemonic::sha256_raw(bytes).to_vec()
}

// The secret every later tag of a correspondence stands on (MontanaFirstContact.firstSecret; named apart from the C door's
// mt_name_first_secret, which the names crate exports on every target): the shared secret, the contact
// root and the ciphertext, by the names crate's own derivation.
#[wasm_bindgen]
pub fn mt_web_first_secret(ss: &[u8], contact_root: &[u8], ct: &[u8]) -> Vec<u8> {
    mt_names::first_secret(ss, contact_root, ct).to_vec()
}

#[wasm_bindgen]
pub fn mt_web_first_tag(contact_root: &[u8], window: u64) -> Vec<u8> {
    mt_names::first_tag(contact_root, window).to_vec()
}

// The core's fast randomness (nonces, invitations, card seeds): the browser's generator reached through the core, as
// mt_random_fast reaches the system's on a phone. Never the root of a person.
#[wasm_bindgen]
pub fn mt_random(n: usize) -> Result<Vec<u8>, JsValue> {
    if n == 0 || 4096 < n {
        return Err(refused("random_length"));
    }
    let mut out = vec![0u8; n];
    mt_mnemonic::random_fast(&mut out).map_err(|_| refused("random"))?;
    Ok(out)
}

// THE GATHER ON THE PAGE'S SECOND THREAD (07.10.2026): a block of the core's full gather -- generate_entropy, the six sources,
// the rule of three, the self-test -- drawn in the page's worker, and the same block mixed into the fast source of the page's
// own thread (seed_fast), so that no draw on the thread the person touches ever pays for a gather.
#[wasm_bindgen]
pub fn mt_web_entropy() -> Result<Vec<u8>, JsValue> {
    match mt_mnemonic::generate_entropy() {
        Ok(block) => Ok(block.to_vec()),
        Err(e) => Err(refused(&format!("entropy: {e}"))),
    }
}

#[wasm_bindgen]
pub fn mt_web_fast_seed(block: &[u8]) -> Result<(), JsValue> {
    mt_mnemonic::seed_fast(block).map_err(|e| refused(&format!("entropy: {e}")))
}

// -- the Business's confirmations (MTBizPhone.keep, MTBizEmail.keep) --------------------------------------------------------

// The service's signed confirmation of a number, opened by the core: the number, the key, the road and the moment as JSON --
// only once the service's pinned ML-DSA key proves the signature (mt_business::attest_open).
#[wasm_bindgen]
pub fn mt_web_attest_open(attest: &[u8]) -> Result<String, JsValue> {
    mt_business::attest_open(attest).map_err(|_| refused("attest"))
}

#[wasm_bindgen]
pub fn mt_web_email_open(attest: &[u8]) -> Result<String, JsValue> {
    mt_business::email_open(attest).map_err(|_| refused("attest"))
}

// -- the organisation's chains (mt-business): the same doors MTBusinessCore.swift calls --------------------------------------

fn biz(e: mt_business::BizError) -> JsValue {
    refused(&e.to_string())
}

// The author's next record, signed by the key the account seed opens inside this one call.
#[wasm_bindgen]
pub fn mt_web_biz_author(
    seed: &[u8],
    roster: &[u8],
    hr: &[u8],
    command: &str,
    at_ms: u64,
) -> Result<Vec<u8>, JsValue> {
    let s = array::<MT_MLDSA_SEED_LEN>(seed, "seed_length")?;
    let (pk, sk) = keypair_from_seed(&s).map_err(|_| refused("keygen"))?;
    mt_business::author(sk.as_bytes(), pk.as_bytes(), roster, hr, command, at_ms).map_err(biz)
}

// The union of two chains: four bytes of how many records of `incoming` were new (u32 LE), then the merged chain.
#[wasm_bindgen]
pub fn mt_web_biz_merge(have: &[u8], incoming: &[u8]) -> Result<Vec<u8>, JsValue> {
    let (merged, added) = mt_business::merge(have, incoming).map_err(biz)?;
    let mut out = u32::try_from(added)
        .unwrap_or(u32::MAX)
        .to_le_bytes()
        .to_vec();
    out.extend_from_slice(&merged);
    Ok(out)
}

#[wasm_bindgen]
pub fn mt_web_biz_view(
    roster: &[u8],
    hr: &[u8],
    viewer_pubkey: &[u8],
    now_ms: u64,
) -> Result<String, JsValue> {
    mt_business::view(roster, hr, viewer_pubkey, now_ms).map_err(biz)
}

#[wasm_bindgen]
pub fn mt_web_biz_slice(hr: &[u8], lane: &[u8]) -> Result<Vec<u8>, JsValue> {
    mt_business::slice(hr, lane).map_err(biz)
}

#[wasm_bindgen]
pub fn mt_web_biz_heads(stream: &[u8]) -> Result<Vec<u8>, JsValue> {
    mt_business::heads(stream).map_err(biz)
}

#[wasm_bindgen]
pub fn mt_web_biz_after(stream: &[u8], their_heads: &[u8]) -> Result<Vec<u8>, JsValue> {
    mt_business::after(stream, their_heads).map_err(biz)
}

#[wasm_bindgen]
pub fn mt_web_biz_keep(org: &[u8], stream: &[u8]) -> Result<Vec<u8>, JsValue> {
    mt_business::keep(org, stream).map_err(biz)
}

#[wasm_bindgen]
pub fn mt_web_biz_member_id(pubkey: &[u8]) -> Result<Vec<u8>, JsValue> {
    mt_business::member_id(pubkey)
        .map(|id| id.to_vec())
        .map_err(biz)
}

#[wasm_bindgen]
pub fn mt_web_biz_invite_id(secret: &[u8]) -> Result<Vec<u8>, JsValue> {
    mt_business::invite_id(secret)
        .map(|id| id.to_vec())
        .map_err(biz)
}
