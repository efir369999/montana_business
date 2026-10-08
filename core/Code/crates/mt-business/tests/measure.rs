// A measure, not a check (ignored by default; run with --release -- --ignored --nocapture): how long the view of an
// organisation of 300 members takes the first time, when every signature is proven, and again, when the proven ones are
// remembered. Numbers for the report come from here and nowhere else.

use std::time::Instant;

use mt_business::body::{Body, CHAT_GROUP, KIND_GENESIS, KIND_LETTER};
use mt_business::codec::to_hex;
use mt_business::fold::member_id_of;
use mt_business::frame::{join_chain, seal, split_chain, Header, CHAIN_C, CHAIN_R};
use mt_business::{after, author_with, heads, merge, view_with};
use mt_crypto::keypair_from_seed;

#[test]
#[ignore]
fn the_view_of_three_hundred_members() {
    const MEMBERS: u32 = 300;
    let t0: u64 = 1_791_234_567_890;
    let (owner_pk, owner_sk) = keypair_from_seed(&[1; 32]).expect("keygen");
    let mut roster = Vec::new();
    let hr = Vec::new();
    let mut clock = t0;
    let put = |roster: &mut Vec<u8>, pk: &[u8], sk: &[u8], cmd: String, at: u64| {
        let frame = author_with(sk, pk, roster, &hr, &cmd, at, None).expect("writes");
        assert_eq!(split_chain(&frame).expect("frame")[0].header.chain, CHAIN_R);
        let (m, _) = merge(roster, &frame).expect("merges");
        *roster = m;
    };
    put(
        &mut roster,
        owner_pk.as_bytes(),
        owner_sk.as_bytes(),
        r#"{"kind":"genesis","name":"Big","owner_name":"O"}"#.into(),
        clock,
    );
    let built = Instant::now();
    for i in 0..MEMBERS {
        let mut seed = [0u8; 32];
        seed[..4].copy_from_slice(&i.to_le_bytes());
        seed[31] = 7;
        let (pk, sk) = keypair_from_seed(&seed).expect("keygen");
        clock += 1000;
        let secret = to_hex(&seed);
        put(
            &mut roster,
            owner_pk.as_bytes(),
            owner_sk.as_bytes(),
            format!("{{\"kind\":\"invite\",\"secret\":\"{secret}\",\"role\":\"employee\",\"expires_ms\":{}}}", t0 + 86_400_000_000),
            clock,
        );
        clock += 1000;
        put(
            &mut roster,
            pk.as_bytes(),
            sk.as_bytes(),
            format!("{{\"kind\":\"join\",\"secret\":\"{secret}\",\"name\":\"M{i}\"}}"),
            clock,
        );
    }
    let records = split_chain(&roster).expect("splits").len();
    println!(
        "built {records} records in {} ms",
        built.elapsed().as_millis()
    );
    mt_business::frame::forget_verified();
    for pass in [
        "cold (every signature proven)",
        "warm (proven ones remembered)",
    ] {
        let at = Instant::now();
        let json = view_with(&roster, &hr, owner_pk.as_bytes(), clock, None).expect("view");
        println!(
            "{pass} view of {records} records: {} ms, {} bytes of JSON",
            at.elapsed().as_millis(),
            json.len()
        );
    }
}

// The view of a busy chat: thirty people and twenty thousand letters in one lane of chain C. How long the view takes cold and
// warm, how long one more letter takes to write (the fold before it and after it), and what a sync by heads costs.
#[test]
#[ignore]
fn the_view_of_a_busy_chat() {
    const PEOPLE: u32 = 30;
    const LETTERS: u32 = 20_000;
    let t0: u64 = 1_791_234_567_890;
    let (owner_pk, owner_sk) = keypair_from_seed(&[1; 32]).expect("keygen");
    let mut roster = Vec::new();
    let mut clock = t0;
    let put = |roster: &mut Vec<u8>, pk: &[u8], sk: &[u8], cmd: String, at: u64| {
        let frame = author_with(sk, pk, roster, &[], &cmd, at, None).expect("writes");
        let (m, _) = merge(roster, &frame).expect("merges");
        *roster = m;
    };
    put(
        &mut roster,
        owner_pk.as_bytes(),
        owner_sk.as_bytes(),
        r#"{"kind":"genesis","name":"Busy","owner_name":"O"}"#.into(),
        clock,
    );
    let mut people = vec![keypair_from_seed(&[1; 32]).expect("keygen")];
    for i in 1..PEOPLE {
        let mut seed = [0u8; 32];
        seed[..4].copy_from_slice(&i.to_le_bytes());
        seed[31] = 9;
        let (pk, sk) = keypair_from_seed(&seed).expect("keygen");
        let secret = to_hex(&seed);
        clock += 1000;
        put(
            &mut roster,
            owner_pk.as_bytes(),
            owner_sk.as_bytes(),
            format!("{{\"kind\":\"invite\",\"secret\":\"{secret}\",\"role\":\"employee\",\"expires_ms\":{}}}", t0 + 86_400_000_000),
            clock,
        );
        clock += 1000;
        put(
            &mut roster,
            pk.as_bytes(),
            sk.as_bytes(),
            format!("{{\"kind\":\"join\",\"secret\":\"{secret}\",\"name\":\"P{i}\"}}"),
            clock,
        );
        people.push((pk, sk));
    }
    let org = split_chain(&roster)
        .expect("splits")
        .iter()
        .find(|r| r.header.kind == KIND_GENESIS)
        .expect("genesis")
        .id;
    let chat = [9u8; 32];
    clock += 1000;
    let open = author_with(
        owner_sk.as_bytes(),
        owner_pk.as_bytes(),
        &roster,
        &[],
        &format!(
            "{{\"kind\":\"open\",\"chat\":\"{}\",\"chat_kind\":{CHAT_GROUP},\"name\":\"All\"}}",
            to_hex(&chat)
        ),
        clock,
        None,
    )
    .expect("opens");
    let mut records = split_chain(&open).expect("frame");
    let mut prev = records[0].id;
    let mut n = records[0].header.n;
    let built = Instant::now();
    for i in 0..LETTERS {
        let (pk, sk) = &people[(i % PEOPLE) as usize];
        clock += 1000;
        n += 1;
        let mut letter = [0u8; 32];
        letter[..4].copy_from_slice(&i.to_le_bytes());
        let body = Body::Letter {
            letter,
            mid: format!("m{i}"),
            letter_kind: 1,
        }
        .encode()
        .expect("encodes");
        let h = Header {
            chain: CHAIN_C,
            kind: KIND_LETTER,
            org,
            lane: chat,
            n,
            prev,
            at_ms: clock,
            author: member_id_of(pk),
        };
        let rec = seal(&h, &body, sk).expect("seals");
        prev = rec.id;
        records.push(rec);
    }
    let hr = join_chain(&records).expect("joins");
    println!(
        "signed {LETTERS} letters in {} ms; the private stream is {} bytes",
        built.elapsed().as_millis(),
        hr.len()
    );
    mt_business::frame::forget_verified();
    for pass in [
        "cold (every signature proven)",
        "warm (proven ones remembered)",
    ] {
        let at = Instant::now();
        let json = view_with(&roster, &hr, owner_pk.as_bytes(), clock, None).expect("view");
        assert!(json.contains("\"All\""), "the chat is in the view");
        println!(
            "{pass} view of {} records: {} ms, {} bytes of JSON",
            records.len(),
            at.elapsed().as_millis(),
            json.len()
        );
    }
    let at = Instant::now();
    let r = split_chain(&roster).expect("splits");
    let h = split_chain(&hr).expect("splits");
    let split_ms = at.elapsed().as_millis();
    let at = Instant::now();
    let f = mt_business::fold::fold(&r, &h, clock, Some(&owner_pk), None);
    let fold_ms = at.elapsed().as_millis();
    let at = Instant::now();
    let json = mt_business::view::render(&f, &owner_pk, clock);
    println!(
        "warm view in parts: split {split_ms} ms, fold {fold_ms} ms, render {} ms ({} bytes)",
        at.elapsed().as_millis(),
        json.len()
    );
    let (pk, sk) = &people[1];
    let at = Instant::now();
    let one = author_with(
        sk.as_bytes(),
        pk.as_bytes(),
        &roster,
        &hr,
        &format!(
            "{{\"kind\":\"letter\",\"chat\":\"{}\",\"mid\":\"m-next\",\"text\":\"hi\"}}",
            to_hex(&chat)
        ),
        clock + 1000,
        None,
    )
    .expect("writes the next letter");
    println!("one more letter written in {} ms", at.elapsed().as_millis());
    let at = Instant::now();
    let (merged, added) = merge(&hr, &one).expect("merges");
    assert_eq!(added, 1);
    println!(
        "merge of one letter into the stream: {} ms",
        at.elapsed().as_millis()
    );
    // A second chat beside the busy one: writing in it folds the roster and its own lane, not the twenty thousand letters.
    let quiet = [8u8; 32];
    let opened = author_with(
        owner_sk.as_bytes(),
        owner_pk.as_bytes(),
        &roster,
        &merged,
        &format!(
            "{{\"kind\":\"open\",\"chat\":\"{}\",\"chat_kind\":{CHAT_GROUP},\"name\":\"Quiet\"}}",
            to_hex(&quiet)
        ),
        clock + 2000,
        None,
    )
    .expect("opens the quiet chat");
    let (with_quiet, _) = merge(&merged, &opened).expect("merges");
    let at = Instant::now();
    author_with(
        sk.as_bytes(),
        pk.as_bytes(),
        &roster,
        &with_quiet,
        &format!(
            "{{\"kind\":\"letter\",\"chat\":\"{}\",\"mid\":\"q1\",\"text\":\"hi\"}}",
            to_hex(&quiet)
        ),
        clock + 3000,
        None,
    )
    .expect("writes in the quiet chat");
    println!(
        "a letter in a second chat beside them: {} ms",
        at.elapsed().as_millis()
    );
    let held = join_chain(&records[..records.len() - 100]).expect("joins");
    let at = Instant::now();
    let theirs = heads(&held).expect("heads");
    let lacking = after(&merged, &theirs).expect("after");
    println!(
        "heads of a phone 101 letters behind: {} bytes, {} ms with the answer; the answer: {} records, {} bytes",
        theirs.len(),
        at.elapsed().as_millis(),
        split_chain(&lacking).expect("splits").len(),
        lacking.len()
    );
}
