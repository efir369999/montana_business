//! BOTH HANDS GIVE THESE BYTES: mt-crypto-native (OpenSSL) and the RustCrypto hand behind the `rustcrypto` feature, run once
//! without it and once with it. What each vector rejects: KAT 2 -- a hand that signs with randomness (hedged ML-DSA) or under a
//! context other than the empty one, or derives other keys from the seed; KAT 3 -- a hand that splits the 64-byte ML-KEM seed
//! otherwise than d = [0..32], z = [32..64], or answers a foreign ciphertext otherwise than FIPS 203's implicit rejection
//! J(z || c). The inputs are permutations, never zeros: a vector on a degenerate input would let a wrong order pass.

use mt_crypto::{
    keypair_from_seed, keypair_from_seed_mlkem, mlkem_decapsulate, mlkem_encapsulate, sign, verify,
    MlkemCiphertext,
};
use sha2::{Digest, Sha256};

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn walk<const N: usize>(start: u8) -> [u8; N] {
    let mut a = [0u8; N];
    for (i, x) in a.iter_mut().enumerate() {
        *x = start.wrapping_add(i as u8).wrapping_mul(7);
    }
    a
}

#[test]
fn kat_2_mldsa_signature_bytes() {
    let msg = b"mt-crypto KAT 2: one message, one signature";
    let (pk, sk) = keypair_from_seed(&walk::<32>(1)).expect("keygen");
    let sig = sign(&sk, msg).expect("sign");
    assert!(verify(&pk, msg, &sig));
    assert_eq!(
        hex(&Sha256::digest(pk.as_bytes())),
        "84cb4eaefc09cc4111fcc33aa0bda62b9bc679076584e221abef206c889011c5"
    );
    assert_eq!(
        hex(&Sha256::digest(sig.as_bytes())),
        "745c783b5df6bc1b4ea80a23f28328fb1e8c697aa3a70a4d587f737c97b82de3"
    );
}

#[test]
fn kat_3_mlkem_keys_and_implicit_rejection() {
    let (pk, sk) = keypair_from_seed_mlkem(&walk::<64>(3)).expect("keygen");
    let foreign = MlkemCiphertext::from_array(walk::<1088>(5));
    let ss = mlkem_decapsulate(&sk, &foreign).expect("decaps");
    assert_eq!(
        hex(&Sha256::digest(pk.as_bytes())),
        "92e87593f49c9d6d5f635d1c9a1019cb94bb9b5c76544aed9c5197aef9fed80a"
    );
    assert_eq!(
        hex(&Sha256::digest(sk.as_bytes())),
        "dc49b16ebeb96e52b69a4f212293fc0ac06f4e36ae7946409ee030479259583a"
    );
    assert_eq!(
        hex(ss.as_bytes()),
        "5ee2ce6be6b8cc097f8105076c0b7db2d25e4ae07d1b2e3283bf0fe9fab88af4"
    );
    let (ct, sent) = mlkem_encapsulate(&pk).expect("encaps");
    assert_eq!(
        mlkem_decapsulate(&sk, &ct).expect("decaps").as_bytes(),
        sent.as_bytes()
    );
}
