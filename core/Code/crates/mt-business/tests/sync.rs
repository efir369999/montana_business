// Sync by the heads of lanes: a phone that names its heads gets exactly what it lacks and nothing it holds; a sibling of a
// fork is found by the digest though its place is held; a stream is kept to one organisation; the heads' layout byte by byte.

use mt_business::codec::to_hex;
use mt_business::frame::{split_chain, CHAIN_R};
use mt_business::{after, author_with, heads, keep, merge};
use mt_crypto::{keypair_from_seed, sha256_raw, PublicKey, SecretKey};

const T0: u64 = 1_791_234_567_890;

struct Person {
    pk: PublicKey,
    sk: SecretKey,
}

fn person(seed: u8) -> Person {
    let (pk, sk) = keypair_from_seed(&[seed; 32]).expect("keygen");
    Person { pk, sk }
}

#[derive(Clone, Default)]
struct Phone {
    roster: Vec<u8>,
    private: Vec<u8>,
}

impl Phone {
    // The record the door writes from this phone's chains, merged into the stream it belongs to; the frame comes back.
    fn write(&mut self, who: &Person, cmd: &str, at: u64) -> Vec<u8> {
        let frame = author_with(
            who.sk.as_bytes(),
            who.pk.as_bytes(),
            &self.roster,
            &self.private,
            cmd,
            at,
            None,
        )
        .unwrap_or_else(|e| panic!("{cmd}: {e}"));
        let recs = split_chain(&frame).expect("one frame");
        let target = if recs[0].header.chain == CHAIN_R {
            &mut self.roster
        } else {
            &mut self.private
        };
        let (m, _) = merge(target, &frame).expect("merges");
        *target = m;
        frame
    }
}

fn org_of(p: &Phone) -> [u8; 32] {
    split_chain(&p.roster).expect("splits")[0].id
}

fn founded(seed: u8) -> (Phone, Person) {
    let owner = person(seed);
    let mut a = Phone::default();
    a.write(
        &owner,
        r#"{"kind":"genesis","name":"Shoes","owner_name":"Anna"}"#,
        T0,
    );
    a.write(
        &owner,
        r#"{"kind":"dept","dept":"c0c1c2c3c4c5c6c7c8c9cacbcccdcecf","name":"Kitchen"}"#,
        T0 + 1000,
    );
    (a, owner)
}

#[test]
fn a_phone_naming_its_heads_gets_what_it_lacks() {
    let (mut a, owner) = founded(41);
    let b = a.clone();
    a.write(
        &owner,
        r#"{"kind":"dept","dept":"d0d1d2d3d4d5d6d7d8d9dadbdcdddedf","name":"Bar"}"#,
        T0 + 2000,
    );
    a.write(&owner, r#"{"kind":"profile","name":"Anna K"}"#, T0 + 3000);
    let delta = after(&a.roster, &heads(&b.roster).expect("heads")).expect("after");
    // Exactly the two records b lacks -- nothing it holds rides again.
    assert_eq!(split_chain(&delta).expect("splits").len(), 2);
    let (caught, added) = merge(&b.roster, &delta).expect("merges");
    assert_eq!(added, 2);
    assert_eq!(caught, a.roster);
    // A phone that holds everything is owed nothing.
    assert!(after(&a.roster, &heads(&a.roster).expect("heads"))
        .expect("after")
        .is_empty());
    // A phone that holds nothing is owed everything.
    assert_eq!(
        after(&a.roster, &heads(&[]).expect("heads")).expect("after"),
        a.roster
    );
}

#[test]
fn a_sibling_of_a_fork_is_found_by_the_digest() {
    let (a, owner) = founded(42);
    // Two records written from the same chains at the same place of one lane: a fork. One phone holds one, the other both.
    let mut one = a.clone();
    let mut other = a.clone();
    one.write(&owner, r#"{"kind":"profile","name":"First"}"#, T0 + 5000);
    other.write(&owner, r#"{"kind":"profile","name":"Second"}"#, T0 + 6000);
    let (both, _) = merge(&one.roster, &other.roster).expect("merges");
    let delta = after(&both, &heads(&one.roster).expect("heads")).expect("after");
    let (caught, _) = merge(&one.roster, &delta).expect("merges");
    assert_eq!(caught, both);
}

#[test]
fn a_stream_is_kept_to_its_organisation() {
    let (a, _) = founded(43);
    let (b, _) = founded(44);
    let (mixed, _) = merge(&a.roster, &b.roster).expect("merges");
    assert_eq!(keep(&org_of(&a), &mixed).expect("keeps"), a.roster);
    assert_eq!(keep(&org_of(&b), &mixed).expect("keeps"), b.roster);
    assert!(keep(&[7u8; 31], &mixed).is_err());
}

#[test]
fn the_heads_layout_byte_by_byte() {
    // Rejects: a big-endian count or place, lanes in arrival order rather than (chain, lane), a digest over ids in arrival
    // order rather than id order.
    let (a, _) = founded(45);
    let recs = split_chain(&a.roster).expect("splits");
    assert_eq!(recs.len(), 2);
    let mut ids = [recs[0].id, recs[1].id];
    ids.sort_unstable();
    let digest = sha256_raw(&[ids[0], ids[1]].concat());
    let mut want = vec![1u8, 0, 0, 0, CHAIN_R];
    want.extend_from_slice(&recs[0].header.lane);
    want.extend_from_slice(&1u64.to_le_bytes());
    want.extend_from_slice(&digest);
    assert_eq!(to_hex(&heads(&a.roster).expect("heads")), to_hex(&want));
    // More heads named than the bytes can hold, and trailing bytes, are refused.
    assert!(after(&a.roster, &[9, 0, 0, 0]).is_err());
    let mut longer = want.clone();
    longer.push(0);
    assert!(after(&a.roster, &longer).is_err());
}
