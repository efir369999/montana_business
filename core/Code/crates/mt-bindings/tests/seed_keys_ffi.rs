use std::ffi::CString;

use mt_bindings::ffi_c::{mt_mnemonic_to_master_seed, mt_seed_keys, mt_seed_keys_from_master};

// The keys from the stretched master seed are the keys of the phrase, byte for byte. A door that derived with another role
// than the account's, or from the entropy without the stretch, gives other keys here. The phrase is BIP-39's vector of 32
// bytes of 0x7f: no degenerate input.
#[test]
fn the_keys_from_the_master_seed_are_the_keys_of_the_phrase() {
    let words = CString::new(
        "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal \
         winner thank year wave sausage worth title",
    )
    .expect("words");
    let (mut pk, mut sk) = (vec![0u8; 1952], vec![0u8; 4032]);
    assert_eq!(
        unsafe { mt_seed_keys(words.as_ptr(), pk.as_mut_ptr(), sk.as_mut_ptr()) },
        0
    );
    let mut master = [0u8; 64];
    assert_eq!(
        unsafe { mt_mnemonic_to_master_seed(words.as_ptr(), master.as_mut_ptr()) },
        0
    );
    let (mut pk2, mut sk2) = (vec![0u8; 1952], vec![0u8; 4032]);
    assert_eq!(
        unsafe { mt_seed_keys_from_master(master.as_ptr(), pk2.as_mut_ptr(), sk2.as_mut_ptr()) },
        0
    );
    assert_eq!(pk, pk2);
    assert_eq!(sk, sk2);
    assert_ne!(pk, vec![0u8; 1952]);
}
