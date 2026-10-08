// The C doors of mt-business answer exactly what the Rust API answers (the Android JNI calls
// that API), report sizes through out_len, and map every error to its MT_ERR_BIZ code; the hand
// header carries the same numbers as the Rust constants.

use std::ffi::CString;
use std::ptr;

use mt_bindings::ffi_business::{
    mt_biz_attest_open, mt_biz_author, mt_biz_invite_id, mt_biz_member_id, mt_biz_merge,
    mt_biz_service_key, mt_biz_slice, mt_biz_view, MT_BIZ_FRAME_MAX, MT_BIZ_ID_LEN,
    MT_ERR_BIZ_ATTEST, MT_ERR_BIZ_COMMAND, MT_ERR_BIZ_DENIED, MT_ERR_BIZ_FRAME,
};
use mt_bindings::{MT_ERR_BUFFER_TOO_SMALL, MT_ERR_INVALID_UTF8, MT_ERR_NULL_PTR, MT_OK};
use mt_business::attest::{test_service_key, Attest, CHANNEL_MESSAGE};
use mt_crypto::{keypair_from_seed, sha256_raw, PublicKey, SecretKey};

const T0: u64 = 1_791_234_567_890;

fn keys(seed: u8) -> (PublicKey, SecretKey) {
    keypair_from_seed(&[seed; 32]).expect("keygen")
}

fn author(
    sk: &SecretKey,
    pk: &PublicKey,
    roster: &[u8],
    hr: &[u8],
    cmd: &[u8],
    at: u64,
) -> (i32, Vec<u8>) {
    let c = CString::new(cmd).expect("no NUL");
    let mut need = 0usize;
    let rc = unsafe {
        mt_biz_author(
            sk.as_bytes().as_ptr(),
            pk.as_bytes().as_ptr(),
            roster.as_ptr(),
            roster.len(),
            hr.as_ptr(),
            hr.len(),
            c.as_ptr(),
            at,
            ptr::null_mut(),
            0,
            &mut need,
        )
    };
    if rc != MT_ERR_BUFFER_TOO_SMALL {
        return (rc, Vec::new());
    }
    let mut out = vec![0u8; need];
    let mut len = 0usize;
    let rc = unsafe {
        mt_biz_author(
            sk.as_bytes().as_ptr(),
            pk.as_bytes().as_ptr(),
            roster.as_ptr(),
            roster.len(),
            hr.as_ptr(),
            hr.len(),
            c.as_ptr(),
            at,
            out.as_mut_ptr(),
            out.len(),
            &mut len,
        )
    };
    out.truncate(len);
    (rc, out)
}

fn merge(have: &[u8], incoming: &[u8]) -> (Vec<u8>, usize) {
    let mut out = vec![0u8; have.len() + incoming.len()];
    let (mut len, mut added) = (0usize, 0usize);
    let rc = unsafe {
        mt_biz_merge(
            have.as_ptr(),
            have.len(),
            incoming.as_ptr(),
            incoming.len(),
            out.as_mut_ptr(),
            out.len(),
            &mut len,
            &mut added,
        )
    };
    assert_eq!(rc, MT_OK);
    out.truncate(len);
    (out, added)
}

fn view(roster: &[u8], hr: &[u8], pk: &PublicKey, now: u64) -> String {
    let mut need = 0usize;
    let rc = unsafe {
        mt_biz_view(
            roster.as_ptr(),
            roster.len(),
            hr.as_ptr(),
            hr.len(),
            pk.as_bytes().as_ptr(),
            now,
            ptr::null_mut(),
            0,
            &mut need,
        )
    };
    assert_eq!(rc, MT_ERR_BUFFER_TOO_SMALL);
    let mut out = vec![0u8; need];
    let mut len = 0usize;
    let rc = unsafe {
        mt_biz_view(
            roster.as_ptr(),
            roster.len(),
            hr.as_ptr(),
            hr.len(),
            pk.as_bytes().as_ptr(),
            now,
            out.as_mut_ptr(),
            out.len(),
            &mut len,
        )
    };
    assert_eq!(rc, MT_OK);
    out.truncate(len);
    String::from_utf8(out).expect("UTF-8")
}

#[test]
fn doors_answer_what_the_rust_api_answers() {
    let (opk, osk) = keys(1);
    let (epk, esk) = keys(4);
    let genesis = br#"{"kind":"genesis","name":"Montana Coffee","owner_name":"Anna"}"#;
    let (rc, frame) = author(&osk, &opk, &[], &[], genesis, T0);
    assert_eq!(rc, MT_OK);
    let rust = mt_business::author(
        osk.as_bytes(),
        opk.as_bytes(),
        &[],
        &[],
        std::str::from_utf8(genesis).expect("UTF-8"),
        T0,
    )
    .expect("authors");
    assert_eq!(frame, rust);
    assert!(frame.len() <= MT_BIZ_FRAME_MAX);
    let (roster, added) = merge(&[], &frame);
    assert_eq!(added, 1);

    let secret = "11".repeat(32);
    let invite = format!(
        "{{\"kind\":\"invite\",\"secret\":\"{secret}\",\"role\":\"employee\",\"expires_ms\":{}}}",
        T0 + 86_400_000
    );
    let (rc, inv) = author(&osk, &opk, &roster, &[], invite.as_bytes(), T0 + 1000);
    assert_eq!(rc, MT_OK);
    let (roster, _) = merge(&roster, &inv);
    let join = format!("{{\"kind\":\"join\",\"secret\":\"{secret}\",\"name\":\"Eva\"}}");
    let (rc, joined) = author(&esk, &epk, &roster, &[], join.as_bytes(), T0 + 2000);
    assert_eq!(rc, MT_OK);
    let (roster, _) = merge(&roster, &joined);

    let mut member = [0u8; MT_BIZ_ID_LEN];
    assert_eq!(
        unsafe { mt_biz_member_id(epk.as_bytes().as_ptr(), member.as_mut_ptr()) },
        MT_OK
    );
    assert_eq!(member, mt_business::member_id(epk.as_bytes()).expect("id"));
    let e = mt_business::codec::to_hex(&member);
    let salary = format!(
        "{{\"kind\":\"salary\",\"member\":\"{e}\",\"coins\":100,\"period\":1,\"from_ms\":{T0}}}"
    );
    let (rc, sal) = author(&osk, &opk, &roster, &[], salary.as_bytes(), T0 + 3000);
    assert_eq!(rc, MT_OK);
    let (hr, _) = merge(&[], &sal);

    let json = view(&roster, &hr, &opk, T0 + 5000);
    assert_eq!(
        json,
        mt_business::view(&roster, &hr, opk.as_bytes(), T0 + 5000).expect("view")
    );
    assert!(json.contains("\"name\":\"Montana Coffee\""));
    // The invitation's id from its secret, as the view names it: the secret itself, or another label's hash, is not in the view.
    let mut invite_id = [0u8; MT_BIZ_ID_LEN];
    assert_eq!(
        unsafe { mt_biz_invite_id([0x11u8; 32].as_ptr(), invite_id.as_mut_ptr()) },
        MT_OK
    );
    assert_eq!(
        invite_id,
        mt_business::invite_id(&[0x11; 32]).expect("invite id")
    );
    let named = format!("\"id\":\"{}\"", mt_business::codec::to_hex(&invite_id));
    assert!(json.contains(&named), "the view names the invitation by it");
    assert!(!json.contains(&format!("\"id\":\"{secret}\"")));

    let mut out = vec![0u8; hr.len()];
    let mut len = 0usize;
    let rc = unsafe {
        mt_biz_slice(
            hr.as_ptr(),
            hr.len(),
            member.as_ptr(),
            out.as_mut_ptr(),
            out.len(),
            &mut len,
        )
    };
    assert_eq!(rc, MT_OK);
    out.truncate(len);
    assert_eq!(out, mt_business::slice(&hr, &member).expect("slice"));
    assert_eq!(out, hr);

    let mut key = vec![0u8; 1952];
    assert_eq!(unsafe { mt_biz_service_key(key.as_mut_ptr()) }, MT_OK);
    assert_eq!(key, mt_business::service_key().expect("key").to_vec());
}

#[test]
fn errors_map_to_their_codes() {
    let (pk, sk) = keys(1);
    let remove = br#"{"kind":"remove","member":"0000000000000000000000000000000000000000000000000000000000000000"}"#;
    assert_eq!(author(&sk, &pk, &[], &[], remove, T0).0, MT_ERR_BIZ_DENIED);
    assert_eq!(
        author(&sk, &pk, &[], &[], b"{not json", T0).0,
        MT_ERR_BIZ_COMMAND
    );
    assert_eq!(
        author(
            &sk,
            &pk,
            &[1, 0, 0, 0, 7],
            &[],
            br#"{"kind":"profile","name":"x"}"#,
            T0
        )
        .0,
        MT_ERR_BIZ_FRAME
    );
    let bad_utf8 = [0xffu8, 0];
    let mut len = 0usize;
    let rc = unsafe {
        mt_biz_author(
            sk.as_bytes().as_ptr(),
            pk.as_bytes().as_ptr(),
            ptr::null(),
            0,
            ptr::null(),
            0,
            bad_utf8.as_ptr().cast(),
            T0,
            ptr::null_mut(),
            0,
            &mut len,
        )
    };
    assert_eq!(rc, MT_ERR_INVALID_UTF8);
    let rc = unsafe {
        mt_biz_author(
            ptr::null(),
            pk.as_bytes().as_ptr(),
            ptr::null(),
            0,
            ptr::null(),
            0,
            bad_utf8.as_ptr().cast(),
            T0,
            ptr::null_mut(),
            0,
            &mut len,
        )
    };
    assert_eq!(rc, MT_ERR_NULL_PTR);
    let rc = unsafe {
        mt_biz_view(
            ptr::null(),
            5,
            ptr::null(),
            0,
            pk.as_bytes().as_ptr(),
            T0,
            ptr::null_mut(),
            0,
            &mut len,
        )
    };
    assert_eq!(rc, MT_ERR_NULL_PTR);
    let junk = [0u8; 40];
    let rc = unsafe { mt_biz_attest_open(junk.as_ptr(), junk.len(), ptr::null_mut(), 0, &mut len) };
    assert_eq!(rc, MT_ERR_BIZ_ATTEST);
}

#[test]
fn attestation_door_follows_the_pinned_key() {
    let (test_pk, test_sk) = test_service_key().expect("keygen");
    let (person, _) = keys(4);
    let a = Attest {
        channel: CHANNEL_MESSAGE,
        at_ms: T0,
        subject: sha256_raw(person.as_bytes()),
        e164: "+79161234567".into(),
    };
    let sealed = a.seal(&test_sk).expect("seals");
    let mut out = vec![0u8; 512];
    let mut len = 0usize;
    let rc = unsafe {
        mt_biz_attest_open(
            sealed.as_ptr(),
            sealed.len(),
            out.as_mut_ptr(),
            out.len(),
            &mut len,
        )
    };
    let pinned = mt_business::service_key().expect("key");
    if pinned == *test_pk.as_bytes() {
        // service_key.hex still holds the TEST key: the test-signed attestation opens.
        assert_eq!(rc, MT_OK);
        out.truncate(len);
        assert_eq!(String::from_utf8(out).expect("UTF-8"), a.to_json());
    } else {
        // The production key is pinned: nothing signed by the test seed may open.
        assert_eq!(rc, MT_ERR_BIZ_ATTEST);
    }
}

#[test]
fn the_header_carries_the_rust_numbers() {
    let header = include_str!("../include/mt_business.h");
    let expect = [
        ("MT_ERR_BIZ_DENIED", i64::from(MT_ERR_BIZ_DENIED)),
        ("MT_ERR_BIZ_ATTEST", i64::from(MT_ERR_BIZ_ATTEST)),
        ("MT_ERR_BIZ_FRAME", i64::from(MT_ERR_BIZ_FRAME)),
        ("MT_ERR_BIZ_COMMAND", i64::from(MT_ERR_BIZ_COMMAND)),
        ("MT_BIZ_ID_LEN", MT_BIZ_ID_LEN as i64),
        ("MT_BIZ_FRAME_MAX", MT_BIZ_FRAME_MAX as i64),
    ];
    for (name, value) in expect {
        let line = header
            .lines()
            .find(|l| l.split_whitespace().nth(1) == Some(name))
            .unwrap_or_else(|| panic!("{name} missing from mt_business.h"));
        let got: i64 = line
            .split_whitespace()
            .nth(2)
            .and_then(|v| v.parse().ok())
            .unwrap_or_else(|| panic!("{name} has no number"));
        assert_eq!(got, value, "{name}");
    }
    for door in [
        "mt_biz_author(",
        "mt_biz_merge(",
        "mt_biz_view(",
        "mt_biz_slice(",
        "mt_biz_attest_open(",
        "mt_biz_service_key(",
        "mt_biz_member_id(",
        "mt_biz_invite_id(",
    ] {
        assert!(header.contains(door), "{door} missing from mt_business.h");
    }
}
