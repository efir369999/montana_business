// THE SECOND HAND OF THE SAME SIX FUNCTIONS (MT Business's web page, 07.10.2026): OpenSSL does not build for WebAssembly, so the
// page's core takes RustCrypto's ML-DSA-65 and ML-KEM-768 behind the feature `rustcrypto`. The bytes are the native ones -- the
// keys from a seed, the deterministic signature under the empty context, the encapsulation key -- held by this crate's own tests
// and its frozen KAT 1 run under the feature, and by mt-bindings tests/wasm_backend_kat.rs. No node or phone build selects it:
// the consensus path stays on mt-crypto-native.

use super::{
    alloc_locked_secret_box, CryptoError, MlkemCiphertext, MlkemPublicKey, MlkemSecretKey,
    MlkemSharedSecret, PublicKey, SecretKey, Signature, KEYPAIR_SEED_SIZE, MLKEM_SEED_SIZE,
    MLKEM_SHARED_SECRET_SIZE,
};
use ml_dsa::{
    EncodedSignature, EncodedVerifyingKey, ExpandedSigningKey, ExpandedSigningKeyBytes, MlDsa65,
    Signature as DsaSignature, VerifyingKey, B32 as DsaSeed,
};
use ml_kem::kem::{Decapsulate, Encapsulate};
use ml_kem::{Ciphertext, Encoded, EncodedSizeUser, KemCore, MlKem768, B32 as KemHalf};
use zeroize::Zeroize;

type KemEk = <MlKem768 as KemCore>::EncapsulationKey;
type KemDk = <MlKem768 as KemCore>::DecapsulationKey;

pub fn keypair_from_seed(
    seed: &[u8; KEYPAIR_SEED_SIZE],
) -> Result<(PublicKey, SecretKey), CryptoError> {
    let xi = DsaSeed::from(*seed);
    let key = ExpandedSigningKey::<MlDsa65>::from_seed(&xi);
    let pk = PublicKey::from_slice(key.verifying_key().encode().as_slice())
        .ok_or(CryptoError::KeygenFailed)?;
    // The crate's SecretKey is the FIPS 204 skEncode form (4032 bytes), byte-stable across both hands: the expanded encoding.
    #[allow(deprecated)]
    let mut expanded = key.to_expanded();
    let sk = SecretKey::from_slice(expanded.as_slice());
    let bytes: &mut [u8] = &mut expanded;
    bytes.zeroize();
    Ok((pk, sk.ok_or(CryptoError::KeygenFailed)?))
}

pub fn sign(sk: &SecretKey, msg: &[u8]) -> Result<Signature, CryptoError> {
    let mut enc = ExpandedSigningKeyBytes::<MlDsa65>::try_from(&sk.as_bytes()[..])
        .map_err(|_| CryptoError::InvalidSecretKey)?;
    // skDecode trusts its input (the crate's own warning): every SecretKey this hand signs with was born by keypair_from_seed in
    // the same process or read back from the person's own store -- the web layer never takes a secret key from outside.
    #[allow(deprecated)]
    let key = ExpandedSigningKey::<MlDsa65>::from_expanded(&enc);
    let bytes: &mut [u8] = &mut enc;
    bytes.zeroize();
    let sig = key
        .sign_deterministic(msg, &[])
        .map_err(|_| CryptoError::SignFailed)?;
    Signature::from_slice(sig.encode().as_slice()).ok_or(CryptoError::SignLengthMismatch)
}

pub fn verify(pk: &PublicKey, msg: &[u8], sig: &Signature) -> bool {
    let Ok(enc_pk) = EncodedVerifyingKey::<MlDsa65>::try_from(&pk.as_bytes()[..]) else {
        return false;
    };
    let Ok(enc_sig) = EncodedSignature::<MlDsa65>::try_from(&sig.as_bytes()[..]) else {
        return false;
    };
    let Some(s) = DsaSignature::<MlDsa65>::decode(&enc_sig) else {
        return false;
    };
    VerifyingKey::<MlDsa65>::decode(&enc_pk).verify_with_context(msg, &[], &s)
}

pub fn keypair_from_seed_mlkem(
    seed: &[u8; MLKEM_SEED_SIZE],
) -> Result<(MlkemPublicKey, MlkemSecretKey), CryptoError> {
    // FIPS 203 ML-KEM.KeyGen_internal(d, z): d = seed[0..32], z = seed[32..64], as the native hand splits it.
    let d = KemHalf::try_from(&seed[..32]).map_err(|_| CryptoError::InvalidInput)?;
    let z = KemHalf::try_from(&seed[32..]).map_err(|_| CryptoError::InvalidInput)?;
    let (dk, ek) = MlKem768::generate_deterministic(&d, &z);
    let pk =
        MlkemPublicKey::from_slice(ek.as_bytes().as_slice()).ok_or(CryptoError::KeygenFailed)?;
    let mut dk_bytes = dk.as_bytes();
    let sk = MlkemSecretKey::from_slice(dk_bytes.as_slice());
    let bytes: &mut [u8] = &mut dk_bytes;
    bytes.zeroize();
    Ok((pk, sk.ok_or(CryptoError::KeygenFailed)?))
}

pub fn mlkem_encapsulate(
    pk: &MlkemPublicKey,
) -> Result<(MlkemCiphertext, MlkemSharedSecret), CryptoError> {
    let enc = Encoded::<KemEk>::try_from(&pk.as_bytes()[..])
        .map_err(|_| CryptoError::InvalidPublicKey)?;
    let ek = KemEk::from_bytes(&enc);
    let (ct, mut ss) = ek
        .encapsulate(&mut rand_core::OsRng)
        .map_err(|_| CryptoError::InvalidInput)?;
    let ct = MlkemCiphertext::from_slice(ct.as_slice()).ok_or(CryptoError::InvalidInput)?;
    let shared = shared_secret(&ss);
    let bytes: &mut [u8] = &mut ss;
    bytes.zeroize();
    Ok((ct, shared?))
}

pub fn mlkem_decapsulate(
    sk: &MlkemSecretKey,
    ct: &MlkemCiphertext,
) -> Result<MlkemSharedSecret, CryptoError> {
    let mut enc = Encoded::<KemDk>::try_from(&sk.as_bytes()[..])
        .map_err(|_| CryptoError::InvalidSecretKey)?;
    let dk = KemDk::from_bytes(&enc);
    let bytes: &mut [u8] = &mut enc;
    bytes.zeroize();
    let c = Ciphertext::<MlKem768>::try_from(&ct.as_bytes()[..])
        .map_err(|_| CryptoError::InvalidInput)?;
    // FIPS 203 implicit rejection: a malformed ciphertext yields a pseudo-random secret, never an error -- as the native hand.
    let mut ss = dk.decapsulate(&c).map_err(|_| CryptoError::InvalidInput)?;
    let shared = shared_secret(&ss);
    let bytes: &mut [u8] = &mut ss;
    bytes.zeroize();
    shared
}

fn shared_secret(ss: &[u8]) -> Result<MlkemSharedSecret, CryptoError> {
    if ss.len() != MLKEM_SHARED_SECRET_SIZE {
        return Err(CryptoError::InvalidInput);
    }
    let mut boxed = alloc_locked_secret_box(MLKEM_SHARED_SECRET_SIZE);
    boxed.copy_from_slice(ss);
    let arr: Box<[u8; MLKEM_SHARED_SECRET_SIZE]> =
        boxed.try_into().map_err(|_| CryptoError::InvalidInput)?;
    Ok(MlkemSharedSecret(arr))
}
