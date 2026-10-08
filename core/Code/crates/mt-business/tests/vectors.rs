// Frozen vectors of the business frame. Every expected value of a layout, a label or a period
// below was produced by tests/vectors_oracle.py: the contract written again in Python from its
// text, with no Rust, so the bytes are pinned by a second implementation. Each vector stands with
// a permutation of its input that must give a different value, and a line naming the wrong
// implementation it rejects. ML-DSA is not in the Python standard library: the one signed frame
// below is frozen from this crate (FIPS 204 deterministic signing) and checked to verify.

use mt_business::attest::{digest, open_with, test_service_key, Attest};
use mt_business::body::{chain_of, Body, Line};
use mt_business::codec::to_hex;
use mt_business::email::{self, EmailAttest};
use mt_business::fold::{invite_id_of, member_id_of, phone_hash_of, shift_lane_of};
use mt_business::frame::{decode, record_id, seal, unsigned_bytes, verify_record, Header};
use mt_business::period::{finished_keys, period_key, PERIOD_DAY, PERIOD_MONTH, PERIOD_WEEK};
use mt_crypto::{keypair_from_seed, sha256_raw, PublicKey, PUBLIC_KEY_SIZE};

const N: u64 = 0x0102_0304_0506_0708;
const AT: u64 = 1_791_234_567_890;
const EXP: u64 = AT + 7 * 86_400_000;

fn seq<const L: usize>(a: u8) -> [u8; L] {
    core::array::from_fn(|i| a.wrapping_add(i as u8))
}

fn pattern(mul: usize, add: usize) -> PublicKey {
    let mut k = [0u8; PUBLIC_KEY_SIZE];
    for (i, b) in k.iter_mut().enumerate() {
        *b = ((i * mul + add) % 256) as u8;
    }
    PublicKey::from_array(k)
}

fn header(kind: u8) -> Header {
    Header {
        chain: chain_of(kind),
        kind,
        org: seq(0x00),
        lane: seq(0x20),
        n: N,
        prev: seq(0x40),
        at_ms: AT,
        author: seq(0x60),
    }
}

fn id_hex(h: &Header, body: &Body) -> String {
    let bytes = unsigned_bytes(h, &body.encode().expect("encodes")).expect("frames");
    to_hex(&record_id(&bytes))
}

fn swap_lane(mut h: Header) -> Header {
    std::mem::swap(&mut h.lane, &mut h.author);
    h
}

fn swap_n_at(mut h: Header) -> Header {
    std::mem::swap(&mut h.n, &mut h.at_ms);
    h
}

enum Perm {
    Body(Box<Body>),
    Header(fn(Header) -> Header),
}

fn check(body: Body, perm: Perm, id: &str, perm_id: &str) {
    let h = header(body.kind());
    assert_eq!(id_hex(&h, &body), id, "kind {:#04x}", body.kind());
    let got = match perm {
        Perm::Body(b) => id_hex(&h, b.as_ref()),
        Perm::Header(f) => id_hex(&f(h.clone()), &body),
    };
    assert_eq!(got, perm_id, "permutation of kind {:#04x}", body.kind());
    assert_ne!(id, perm_id);
}

#[test]
fn header_layout_byte_by_byte() {
    // Rejects: any header field order other than magic, chain, kind, org, lane, n, prev, at_ms,
    // author, blen, and big-endian integers (n would read 0102...08, not 0807...01).
    let h = header(0x07);
    let body = Body::Remove { member: seq(0x80) };
    let bytes = unsigned_bytes(&h, &body.encode().expect("encodes")).expect("frames");
    assert_eq!(
        to_hex(&bytes),
        "4d5442315207000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f\
         202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f0807060504030201\
         404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5fd26ee60da1010000\
         606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f20000000\
         808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"
    );
}

#[test]
fn every_kind_is_frozen_with_a_distinguishing_permutation() {
    // Rejects: owner_name written before the organisation name.
    check(
        Body::Genesis {
            name: "Montana Coffee".into(),
            owner_key: pattern(7, 3),
            owner_name: "Анна Петрова".into(),
            owner_card: "montana://card/anna".into(),
        },
        Perm::Body(Box::new(Body::Genesis {
            name: "Анна Петрова".into(),
            owner_key: pattern(7, 3),
            owner_name: "Montana Coffee".into(),
            owner_card: "montana://card/anna".into(),
        })),
        "2d45de71320297a19db0b88de5413c42b1cbc53a9b1636df1c65de220ce7df3b",
        "458e36ce1b7734f1195d5b59356231701625b5b2c5440476202e3771520278e6",
    );
    // Rejects: a header that writes the author before the lane.
    check(
        Body::Invite {
            invite_id: seq(0x80),
            role: 3,
            dept: seq(0xC0),
            expires_ms: EXP,
            phone_bound: true,
        },
        Perm::Header(swap_lane),
        "27824df94a559819c569bbce029336a63d6827125358cd5aac947a71a78ee955",
        "1c705663e99ef6067005735613edf0463ed07b70f05b52c2043db3c321f3f57a",
    );
    // Rejects: the card written before the name.
    check(
        Body::Join {
            secret: seq(0x80),
            key: pattern(11, 5),
            name: "Борис".into(),
            card: "montana://card/boris".into(),
        },
        Perm::Body(Box::new(Body::Join {
            secret: seq(0x80),
            key: pattern(11, 5),
            name: "montana://card/boris".into(),
            card: "Борис".into(),
        })),
        "9f9a6aeb15b3664d515573c0a33b80e8404675928eb7f7bd0aa3c6f03424728e",
        "d9ae66001b96c1734e4d602513e45b06539fc767d693a4d1fc6fc6e06c0b3c24",
    );
    // Rejects: a header that writes the author before the lane.
    check(
        Body::Role {
            member: seq(0x80),
            role: 2,
        },
        Perm::Header(swap_lane),
        "d6d9d2ccb1dc2ccf85f1c7f8a255d5cfd284e33c6480c9022e3ae2a06b952f09",
        "e1290f0b96955f29098206a9518181ef553278ea184f54f3fca891f6ca4d76f6",
    );
    // Rejects: a header that writes the author before the lane.
    check(
        Body::Dept {
            dept: seq(0xC0),
            name: "Кухня".into(),
            archived: false,
        },
        Perm::Header(swap_lane),
        "f329fcdd2a8d0fb5f86f884c784d0115d9caecb6b2219724b96c381d1801a60e",
        "d1bd933a2ab5ed04097d1ef87ed57083b46c4bdc758e2b4e2b0897257e938269",
    );
    // Rejects: a header that writes the author before the lane.
    check(
        Body::Assign {
            member: seq(0x80),
            dept: seq(0xC0),
            title: "Бариста".into(),
        },
        Perm::Header(swap_lane),
        "72f07e206b85b1384af6174bc5df37b1b21da12e2bd8c5964096e38ab778927a",
        "82f90de60b9d6a561a4d6fd41fdac5bc09e682c5d4a6b8f047e515cd164776c6",
    );
    // Rejects: a header that writes the author before the lane.
    check(
        Body::Remove { member: seq(0x80) },
        Perm::Header(swap_lane),
        "59b59899c230acc338786968dde3f28581e703f7237d89ae9c5d51c4b2ff367f",
        "5c03c061786c275568c0a8baeafdc9b2c60eea06d7f281cf8cd2a32f4aeb3cbe",
    );
    // Rejects: a header that writes the author before the lane.
    check(
        Body::Rekey {
            member: seq(0x80),
            key: pattern(11, 5),
        },
        Perm::Header(swap_lane),
        "b4871d129da1817804035a927c731aa3dccd04453fb5caaa8c933bd284f5ca50",
        "692e680f4af52b2b014dff842bac8d1fa52fdfba16c3000262d5713a270ee68a",
    );
    // Rejects: a header that writes the author before the lane.
    check(
        Body::Revoke {
            invite_id: seq(0xA0),
        },
        Perm::Header(swap_lane),
        "1b6735d200a5ebb4aa96b48fc52bfbbd978fca456e2c359c663ad8037365288a",
        "91873e608b01206c32d22d6576043af0d887ca2ad237db84194123afcd887285",
    );
    // Rejects: a header that writes at_ms where n belongs.
    check(
        Body::Offer {
            item: seq(0xD0),
            title: "Латте".into(),
            price: 350,
            stock: 20,
            active: true,
        },
        Perm::Header(swap_n_at),
        "9863cb18519415f4a4be162918cb2c4ac66c1b0aa1f230a1c136ca49717c2005",
        "d6acc6b6aee7f3db4e3a71b08058fffee4daebb5e0d210427abedc221b0678c8",
    );
    // Rejects: the card written before the name.
    check(
        Body::Profile {
            name: "Анна".into(),
            card: "montana://card/anna2".into(),
        },
        Perm::Body(Box::new(Body::Profile {
            name: "montana://card/anna2".into(),
            card: "Анна".into(),
        })),
        "03056fd1cfe7d2c18cf6cae1389a2a87bba3659bc12fef3b60700ad893f20114",
        "43e9ff359243310840e5f2e80fd1ab06f9190341d5283f1ee9f9387b6aeeeeda",
    );
    // Rejects: a header that writes the author before the lane.
    check(
        Body::PhoneBind {
            attest: seq::<40>(0x10).to_vec(),
        },
        Perm::Header(swap_lane),
        "cc97f0747f101e3500a41ffa16bad30ffb555a761cdf9a41310db8b557577a26",
        "181bb074bcf967cff7ed42342774707de4358ba53d4358511aad724f9a5f10c5",
    );
    // Rejects: the phone hash written before the invite id.
    check(
        Body::InvitePhone {
            invite_id: seq(0x80),
            phone_hash: seq(0xA0),
        },
        Perm::Body(Box::new(Body::InvitePhone {
            invite_id: seq(0xA0),
            phone_hash: seq(0x80),
        })),
        "a80dc0e780d961b0d4e4692cac42fecd17591ec7cb24d9365bcbc2dc2b6a9234",
        "044c57947640d1944690d84f62bc86aac4af9bfdd6d858c799b7c1be060045cb",
    );
    // Rejects: from_ms written where the coins belong.
    check(
        Body::Salary {
            member: seq(0x80),
            coins: 100,
            period: 1,
            from_ms: AT,
        },
        Perm::Body(Box::new(Body::Salary {
            member: seq(0x80),
            coins: AT,
            period: 1,
            from_ms: 100,
        })),
        "bf58434c3595e36c99f5eac46838b34097c1d4831451f423be74086699871176",
        "32ae7e15c60d7efb0cce86ff564a722061799943f8ca678d5f4e280fffa2af11",
    );
    // Rejects: a header that writes at_ms where n belongs.
    check(
        Body::Pay {
            member: seq(0x80),
            pay_kind: 1,
            period_key: 0x1000_5078,
            coins: 100,
            note: "Октябрь".into(),
        },
        Perm::Header(swap_n_at),
        "c10ff2326908c599849f1c108ba7268169763ffc5947bace41d20a734fbbc6eb",
        "9c039f4b843f461ceedc1843985cf7380817b0642a2da6b2c602ef57531e9852",
    );
    // Rejects: a header that writes the author before the lane.
    check(
        Body::Receipt { record: seq(0xA0) },
        Perm::Header(swap_lane),
        "cddc2b967f888c0630825eb89c4bfb3e52b0f8516832d047e60d874c75590ec4",
        "5fb6fd209af88a4ebdf1c7d2c3c5919822820aecccdf853bd730a697521bad9a",
    );
    // Rejects: a header that writes the author before the lane.
    check(
        Body::Redeem {
            item: seq(0xD0),
            qty: 2,
            coins: 700,
        },
        Perm::Header(swap_lane),
        "1366303a5c56e07aa3358258ae52bb2e67a10a51bea295de9ace15897fcbf10c",
        "33ee424dfb807b8b0ef5345a239c2044a9b8fed30f119a84b01422bf99a53c92",
    );
    // Rejects: a header that writes the author before the lane.
    check(
        Body::Fulfil { record: seq(0xA0) },
        Perm::Header(swap_lane),
        "0d5fffdb2a87de762eb14ed8c73c90f2542d6aab17ed15180cfe5b57c56a5815",
        "3c6395e25aa748316202063900a05048aecfce4d53d3a0b79d41e0500e23e043",
    );
}

#[test]
fn labelled_identifiers() {
    // Rejects: a member id over the key bytes in reverse order.
    assert_eq!(
        to_hex(&member_id_of(&pattern(7, 3))),
        "4200956fa8d77f908ed3b3491a6cf4c39be1d0188db7c2ed95340630d1358a48"
    );
    let mut rev = *pattern(7, 3).as_bytes();
    rev.reverse();
    assert_eq!(
        to_hex(&member_id_of(&PublicKey::from_array(rev))),
        "996dc98b60ebed5159fcaf6c12dd470537e737e24fd72686048c9626b73c79e4"
    );
    // Rejects: an invite id over the secret in reverse order.
    assert_eq!(
        to_hex(&invite_id_of(&seq(0x80))),
        "e6074c47ae2e15c1688da9e666c93e61ec5fd5da061b55c2b224bed90c42611a"
    );
    let mut secret: [u8; 32] = seq(0x80);
    secret.reverse();
    assert_eq!(
        to_hex(&invite_id_of(&secret)),
        "6599c67f323ed9af2b2faf39439cddea7f0fd3e2847e97663b2756b34b13ee89"
    );
    // Rejects: a phone hash over e164 before the org (the oracle's permuted value).
    let phone = to_hex(&phone_hash_of(&seq(0x00), "+79161234567"));
    assert_eq!(
        phone,
        "19a2bd90bbc1ee07feb6eb5b590f08dff2ecaf5d43f157235803ddc0ada2cf10"
    );
    assert_ne!(
        phone,
        "d449f4073b962307068988d6ba1ff7c3125d80e77e23452111379a024731e42d"
    );
}

fn attest(channel: u8, at_ms: u64, e164: &str) -> Attest {
    Attest {
        channel,
        at_ms,
        subject: seq(0x00),
        e164: e164.into(),
    }
}

#[test]
fn attestation_layout_and_signature() {
    // Rejects: a big-endian at_ms and an e164 written backwards (both oracle permutations differ).
    let a = attest(1, AT, "+79161234567");
    let unsigned = a.unsigned().expect("encodes");
    assert_eq!(
        to_hex(&unsigned),
        "4d5442410101d26ee60da1010000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0c2b3739313631323334353637"
    );
    assert_eq!(
        to_hex(&digest(&unsigned)),
        "24160edb6c3bf5a2268cbd69b5ffc8fe0531d61ef3b1809300e43abc3375b450"
    );
    let e164_rev = attest(1, AT, "+77654321619").unsigned().expect("encodes");
    assert_eq!(
        to_hex(&digest(&e164_rev)),
        "f1064256c98e735bfa50f0429ca2d4d4cb6b548c846ea4b200e660d900b86490"
    );
    let at_be = u64::from_le_bytes(AT.to_be_bytes());
    let at_rev = attest(1, at_be, "+79161234567")
        .unsigned()
        .expect("encodes");
    assert_eq!(
        to_hex(&digest(&at_rev)),
        "2b58ed70a813b9fe6da3f3a1d087f0401f87da8e2b0fe8a7886e3aa929b45184"
    );
    let (pk, sk) = test_service_key().expect("keygen");
    let sealed = a.seal(&sk).expect("seals");
    assert_eq!(open_with(&pk, &sealed).expect("opens"), a);
    assert_eq!(&sealed[..unsigned.len()], &unsigned[..]);
}

#[test]
fn email_attestation_layout_and_signature() {
    // Rejects: an address written backwards, and a digest taken under the number's label (both oracle permutations differ).
    let a = EmailAttest {
        at_ms: AT,
        subject: seq(0x00),
        email: "anna@montana.quest".into(),
    };
    let unsigned = a.unsigned().expect("encodes");
    assert_eq!(
        to_hex(&unsigned),
        "4d54424501d26ee60da1010000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f12616e6e61406d6f6e74616e612e7175657374"
    );
    assert_eq!(
        to_hex(&email::digest(&unsigned)),
        "074b97c4b1002f335830cdfbc1861810b5f400bf449040e0103100d0a753a4a5"
    );
    // The address written backwards is no address (the core refuses to seal it), so its frame is built by hand: the bytes
    // an implementation that reversed the address would sign.
    let mut rev = unsigned.clone();
    let tail = unsigned.len() - a.email.len();
    rev[tail..].reverse();
    assert_eq!(
        to_hex(&email::digest(&rev)),
        "52123d78572d9c1d94121fc2bebd0fe578f0076008d1f4cbc865e8bb7b8bd460"
    );
    assert_eq!(
        to_hex(&digest(&unsigned)),
        "d5f4fa80ec4703ecec7c8ac7de9dd3c0fa0231acc3066a195f0992a0eeeafbc8"
    );
    let (pk, sk) = test_service_key().expect("keygen");
    let sealed = a.seal(&sk).expect("seals");
    assert_eq!(email::open_with(&pk, &sealed).expect("opens"), a);
    assert_eq!(&sealed[..unsigned.len()], &unsigned[..]);
}

#[test]
fn one_signed_frame_is_frozen() {
    // Rejects: randomized (hedged) ML-DSA signing or a signature over the raw bytes instead of
    // the record id; frozen from this crate, ML-DSA has no second implementation in the oracle.
    let (pk, sk) = keypair_from_seed(&[0x11; 32]).expect("keygen");
    let body = Body::Remove { member: seq(0x80) }
        .encode()
        .expect("encodes");
    let rec = seal(&header(0x07), &body, &sk).expect("seals");
    assert!(verify_record(&rec, &pk));
    assert_eq!(decode(&rec.bytes).expect("decodes"), rec);
    assert_eq!(
        to_hex(&rec.id),
        "59b59899c230acc338786968dde3f28581e703f7237d89ae9c5d51c4b2ff367f"
    );
    assert_eq!(to_hex(&sha256_raw(&rec.bytes)), FROZEN_FRAME_SHA256);
}

const FROZEN_FRAME_SHA256: &str =
    "bd132cb22e6c95cedb320b417417bac7d12099b058f140508c96f61672754880";

#[test]
fn utc_period_boundaries() {
    // Rejects: local time, weeks starting on Sunday or Thursday, months of 30 days, and keys
    // without their period kind (the oracle computes all three with datetime).
    let cases: [(u8, u64, u32); 13] = [
        (PERIOD_DAY, 1_791_244_799_999, 0x1000_50fb),
        (PERIOD_DAY, 1_791_244_800_000, 0x1000_50fc),
        (PERIOD_DAY, 1_835_438_400_000, 0x1000_52fb),
        (PERIOD_WEEK, 1_791_158_399_999, 0x2000_0b91),
        (PERIOD_WEEK, 1_791_158_400_000, 0x2000_0b92),
        (PERIOD_WEEK, 345_599_999, 0x2000_0000),
        (PERIOD_WEEK, 345_600_000, 0x2000_0001),
        (PERIOD_MONTH, 1_769_903_999_999, 0x3000_02a0),
        (PERIOD_MONTH, 1_769_904_000_000, 0x3000_02a1),
        (PERIOD_MONTH, 1_835_481_599_999, 0x3000_02b9),
        (PERIOD_MONTH, 1_835_481_600_000, 0x3000_02ba),
        (PERIOD_MONTH, 1_798_761_599_999, 0x3000_02ab),
        (PERIOD_MONTH, 1_798_761_600_000, 0x3000_02ac),
    ];
    for (period, ms, key) in cases {
        assert_eq!(period_key(period, ms), Some(key), "period {period} at {ms}");
    }
}

#[test]
fn due_keys_follow_the_oracle() {
    // Rejects: counting the current, unfinished period; skipping the period that holds from_ms;
    // and more than twelve periods back.
    let day = 86_400_000u64;
    assert_eq!(
        finished_keys(PERIOD_DAY, AT, AT + 3 * day + 3_600_000),
        vec![0x1000_50fb, 0x1000_50fc, 0x1000_50fd]
    );
    let cap: Vec<u32> = (0x1000_50ef..=0x1000_50fa).collect();
    assert_eq!(finished_keys(PERIOD_DAY, 0, AT), cap);
    assert_eq!(
        finished_keys(PERIOD_WEEK, 1_788_775_200_000, 1_791_367_200_000),
        vec![0x2000_0b8e, 0x2000_0b8f, 0x2000_0b90, 0x2000_0b91]
    );
    assert_eq!(
        finished_keys(PERIOD_MONTH, 1_768_435_200_000, 1_775_001_600_000),
        vec![0x3000_02a0, 0x3000_02a1, 0x3000_02a2]
    );
    let months: Vec<u32> = (0x3000_029d..=0x3000_02a8).collect();
    assert_eq!(
        finished_keys(PERIOD_MONTH, 1_577_836_800_000, 1_791_244_800_000),
        months
    );
    assert!(finished_keys(PERIOD_DAY, AT + day, AT).is_empty());
}

fn line1() -> Line {
    Line {
        item: seq(0xE0),
        size: "42".into(),
        qty: 2,
        coins: 900,
    }
}

fn line2() -> Line {
    Line {
        item: seq(0xF0),
        size: String::new(),
        qty: 1,
        coins: 0,
    }
}

#[test]
fn every_kind_of_contract_1_1_is_frozen_with_a_distinguishing_permutation() {
    let (d1, d2, p, g) = (
        seq::<16>(0xC0),
        seq::<16>(0xD0),
        seq::<16>(0x30),
        seq::<16>(0x50),
    );
    let (m1, m2) = (seq::<32>(0x80), seq::<32>(0xA0));
    let item = |title: &str, sizes: &str| Body::Item {
        item: d1,
        title: title.into(),
        sizes: sizes.into(),
        unit: "пара".into(),
        active: true,
    };
    // Rejects: the size grid written before the item's name.
    check(
        item("Кроссовки", "40,41,42"),
        Perm::Body(Box::new(item("40,41,42", "Кроссовки"))),
        "3fe92ccc9b82bd016449152f6c894f177c3fb504ab452fdae479719c6b5d8d87",
        "5699fb1ff6c258aec0596f457e1c8e116d3f42a6e5709f44b572ec6ddcd978e9",
    );
    let node = |name: &str, place: &str| Body::Node {
        node: d1,
        node_kind: 2,
        name: name.into(),
        place: place.into(),
        active: true,
    };
    // Rejects: the node's place written before its name.
    check(
        node("Склад Север", "Химки"),
        Perm::Body(Box::new(node("Химки", "Склад Север"))),
        "009a5734d47e1d8fc9f3638b0036c7c28611b4b907fe0e1d447aafa7ccb7bb0d",
        "59234b450b98bc0f7257eb0d852c1240a8cf5d2034edd10cb58d4a81eee6663c",
    );
    // Rejects: the member written before the node (the oracle's permuted body is member, node, flag).
    let staff = Body::NodeStaff {
        node: d1,
        member: m1,
        on: true,
    };
    let staff_id = id_hex(&header(0x0E), &staff);
    assert_eq!(
        staff_id,
        "64ff1408cfb25a74af11f25a5c384bbcc662d2004df3ad3fc85aa2ee3627bc4e"
    );
    // Rejects: a shift's lane and author swapped (the shift lies on the member's lane, signed by the member).
    check(
        Body::ShiftOpen { node: d1 },
        Perm::Header(swap_lane),
        "301be01f823e540caffe8a7e22c7fca5e2e4dc44954f2e85b833bf00a3aa889c",
        "ae31af42618ce2a37ecff0d4fa3a2e8c7c56e612d667eb66851df45b3ed8edaa",
    );
    // Rejects: n and at_ms swapped in the header.
    check(
        Body::ShiftClose {
            note: "смена".into(),
        },
        Perm::Header(swap_n_at),
        "c62ac534bdb083d8d39bf11a76fe756949ff9dbbffbb3e69193490eb68192854",
        "3e6b4c24f104a38a3dba5c6885ede5b619afeb11e9c09692dab0cb6c78abc990",
    );
    check(
        Body::ShiftConfirm { record: m2 },
        Perm::Header(swap_lane),
        "6c0167e9c59a359b5eb781277f36fb17b7f3119843baf3e91e9fd0f91dff0df0",
        "87d192f27b5522081cc9021ead662e732f122e3bd616b90a957fa5e197d21b8a",
    );
    let order = |lines: Vec<Line>| Body::Order {
        from: d1,
        to: d2,
        lines,
        source: 2,
        voice: m1,
        text: "две пары".into(),
    };
    // Rejects: the order's lines sorted or reversed -- their order is the author's.
    check(
        order(vec![line1(), line2()]),
        Perm::Body(Box::new(order(vec![line2(), line1()]))),
        "e21738d021b435d4cc507515d25648f31b8f76cd20409ad9ed2807726320c407",
        "12611e29738b0b99511dc3d32d7c6be84f78da7cbbe6850a9ebd638e8fab6df4",
    );
    check(
        Body::Confirm { node: d1 },
        Perm::Header(swap_n_at),
        "7d7848e7b1c5a8e6bb65ff4e6cabae3ac018cd12108f601d0e84ed73b8a87ddc",
        "af85856d24ba6a771c520c1648f229b0b1daad5392aa0dff12824ba8281f003f",
    );
    // Rejects: the node written before the place.
    check(
        Body::Pack {
            place: p,
            node: d1,
            lines: vec![line1()],
        },
        Perm::Body(Box::new(Body::Pack {
            place: d1,
            node: p,
            lines: vec![line1()],
        })),
        "ca3c96a809f18022489fe80bd319178de0ec9899221f73557f79fdda841d8f0d",
        "fe87a7916bcab86d0dd843157594bd55bf8a0175bef6b54733209dce9d9fe391",
    );
    // Rejects: from and to swapped -- the box would travel backwards.
    check(
        Body::Handoff {
            place: p,
            from: d1,
            to: d2,
        },
        Perm::Body(Box::new(Body::Handoff {
            place: p,
            from: d2,
            to: d1,
        })),
        "916247cdb38a1d53a4e3d0f4e916b44576c7f3cca7d962ac21f4c3105ab4e819",
        "aee8d6f935ee12311086c70bb2e51875aaaed64fd8a37af7f8e1b99f78add79a",
    );
    // Rejects: the photo's hash written before the keeper's note (the oracle's permuted body).
    let accept = Body::Accept {
        place: p,
        node: d2,
        condition: 3,
        lines: vec![line1()],
        note: "одна пара".into(),
        photo: m2,
    };
    assert_eq!(
        id_hex(&header(0x45), &accept),
        "5f11b79f39adcfdc542361c86ca2ce1419988c0c7ffd3ed24b7c94e79e8a3de3"
    );
    check(
        Body::Scan { place: p, node: d1 },
        Perm::Header(swap_lane),
        "1d4b6f89a38216b801ad61e95c1bdc2d7f1eee17c28f5f173172aacc1b33e012",
        "a8e52e547779c05cee0e2ce6dade5c0167aaf97114713b8cb1c28991d8995df1",
    );
    check(
        Body::Shelf {
            node: d2,
            lines: vec![line1()],
        },
        Perm::Header(swap_n_at),
        "8d9a0eefb8d8e2da6bd55e938c858c771b09578ce0f94cb48d41c0865d8d51d4",
        "43b668d350986736f1027c1fba1650480d34383e3a3adbbcddcc54eb8f254040",
    );
    let sale = Body::Sale {
        node: d2,
        lines: vec![line2()],
    };
    check(
        sale.clone(),
        Perm::Header(swap_n_at),
        "197631313154f5832b7706ed2bad4e6c091419267eb03eade70059efffa01498",
        "60e214ec2c260d9d0abe23806b6d3ef123b750acf3f9baf817eb6dab274e25e3",
    );
    // Rejects: a line's quantity and coins written big-endian (the oracle's body_big ends 00000001 ...).
    assert_eq!(
        to_hex(&sale.encode().expect("encodes")),
        "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf0100f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff0000010000000000000000000000"
    );
    check(
        Body::Issue {
            place: p,
            issue_kind: 2,
            note: "подошва".into(),
        },
        Perm::Header(swap_lane),
        "0d79ebb6f6802103b438d7b5b18b4dd51f624badab5953aab5a3a8a2b714c31d",
        "58b3809f5ce63ef5baaa1cb3d365a73be7885bc748cc3fbda7810753278f25cd",
    );
    check(
        Body::Cancel {
            note: "не нужно".into(),
        },
        Perm::Header(swap_n_at),
        "cba9c8f93b8a1760755a13a9ff30610caf7e52bc7ff4beb0265b4db847dabfc7",
        "fbac947096e49038d8f3b290a5befb5ab9b7a5332dde7763d1d878dc420b56c3",
    );
    // Rejects: the carrying group written before the department.
    check(
        Body::Open {
            chat_kind: 2,
            name: "Новости".into(),
            dept: d1,
            group: g,
        },
        Perm::Body(Box::new(Body::Open {
            chat_kind: 2,
            name: "Новости".into(),
            dept: g,
            group: d1,
        })),
        "4098c5656bcba56cc471dae933d5c587593581330e96c038b7dc65415069d1a9",
        "5dbe15b3481b0f7d562dfe7476f6883a2c0ae0feca7fcbff70440492d16c1620",
    );
    // Rejects: the letter's kind written before its mid (the oracle's permuted body).
    let letter = Body::Letter {
        letter: m1,
        mid: "0198a2b3c4d5".into(),
        letter_kind: 1,
    };
    assert_eq!(
        id_hex(&header(0x62), &letter),
        "35f9a80132dbd42d2933159238be1c2083ffbc3451f40d91630aa80a0d7feb1f"
    );
    // Rejects: the new words' hash written before the record they edit.
    check(
        Body::Edit {
            record: m1,
            letter: m2,
        },
        Perm::Body(Box::new(Body::Edit {
            record: m2,
            letter: m1,
        })),
        "35ca711f2f094632f49e31cea93638bd6ea8544ed5cbadac3e998a4d3856e835",
        "0e095aaa488415e6cde33ba0445abfd8a6c3b0ea44b1fed0c6631ce0837957ac",
    );
    check(
        Body::Delete { record: m2 },
        Perm::Header(swap_lane),
        "ac9dadbbfce7fc50c959fb8a0069f06134277504b8efae6467985df91e3677ff",
        "1293b5286823d32d2141e0e33ffdb4c7b4622aae3ada06b7141bb48453cb94b1",
    );
    // Rejects: the chat's lane taken from the wrong place, and a switch written as anything but its one byte -- on and off
    // are two records (the oracle's TRACK and TRACK_OFF).
    check(
        Body::Track { on: true },
        Perm::Header(swap_lane),
        "50f1f5abfddb2bbad80065c23064328da29410c8bbc59be9141d2cb08c0c96e2",
        "8392b782b17e1619822ead794cc67b9bb1895d073132bb5b450399348c040601",
    );
    assert_eq!(
        id_hex(&header(0x65), &Body::Track { on: false }),
        "4e890406d2597b2616905687e8584e75e858cf333f0224b322fdb778522f058b"
    );
    // Rejects: the window's seconds written high byte first (the oracle's TRACK_WINDOW, its perm the same number big-endian).
    check(
        Body::TrackWindow { window_s: 1800 },
        Perm::Body(Box::new(Body::TrackWindow {
            window_s: 1800u32.swap_bytes(),
        })),
        "92af4d8735a9df5493148c4a61a4920fa249209d093555e77b1c09b42c30bdff",
        "ab9d592be0a6eb5994d2e527e778fb6680073827a653b6bfdcb58bdbb14811e9",
    );
}

#[test]
fn labelled_identifiers_of_contract_1_1() {
    let m1 = seq::<32>(0x80);
    // Rejects: the member's id reversed under the shift label.
    assert_eq!(
        to_hex(&shift_lane_of(&m1)),
        "08009f85a1e470cbf314586940d6a688eb2ed8bdea3a46bf19e5034991ce8d86"
    );
    let mut reversed = m1;
    reversed.reverse();
    assert_eq!(
        to_hex(&shift_lane_of(&reversed)),
        "377a0f501878ba1e4f1d5f8e6da2a17593dc7d372ea1fba001ab250948da614c"
    );
    // Rejects: the letter's words hashed before its mid.
    let words = "Кухня в одиннадцать".as_bytes();
    let named = mt_crypto::hash(mt_business::domain::DOMAIN_LETTER, &[b"m3", &[0u8], words]);
    assert_eq!(
        to_hex(&named),
        "24995dccb21e32eed73d6c5a88f4897df69460eb7d774077df8de5ee96a27b1a"
    );
    let swapped = mt_crypto::hash(mt_business::domain::DOMAIN_LETTER, &[words, &[0u8], b"m3"]);
    assert_eq!(
        to_hex(&swapped),
        "bb0c7bc3328f880e2e87e511614fb2c13b95ff947c073bd223f26e60a18c14b7"
    );
}
