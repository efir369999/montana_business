// The signer of the phone service. The Python service never holds the key: it calls
//   mt-business-attest keygen DIR   -- DIR/service.seed (0600, never overwritten), prints the public key hex
//   mt-business-attest sign DIR     -- JSON {e164, subject, channel, at_ms} on stdin, MTBA hex on stdout
//   mt-business-attest sign-email DIR -- JSON {email, subject, at_ms} on stdin, MTBE hex on stdout (the address confirmed by mail)
//   mt-business-attest pubkey DIR   -- the public key hex of DIR/service.seed

use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use mt_business::attest::{valid_e164, Attest, CHANNEL_BOT, CHANNEL_MESSAGE};
use mt_business::codec::{hex_array, to_hex};
use mt_business::email::{valid_email, EmailAttest};
use mt_crypto::{keypair_from_seed, PublicKey, SecretKey, KEYPAIR_SEED_SIZE};
use serde_json::Value;
use zeroize::Zeroizing;

const SEED_FILE: &str = "service.seed";
const PUBLIC_FILE: &str = "service_key.hex";
const STDIN_MAX: u64 = 4096;

fn keys_from(seed: &[u8; KEYPAIR_SEED_SIZE]) -> Result<(PublicKey, SecretKey), String> {
    keypair_from_seed(seed).map_err(|e| format!("keygen: {e}"))
}

#[cfg(unix)]
fn keygen(dir: &Path) -> Result<String, String> {
    use std::fs::{DirBuilder, OpenOptions};
    use std::io::Write;
    use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt};

    DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(dir)
        .map_err(|e| format!("{}: {e}", dir.display()))?;
    let entropy = mt_mnemonic::generate_entropy().map_err(|e| format!("entropy: {e}"))?;
    let mut seed = Zeroizing::new([0u8; KEYPAIR_SEED_SIZE]);
    seed.copy_from_slice(&entropy[..KEYPAIR_SEED_SIZE]);
    let (pk, _sk) = keys_from(&seed)?;
    let seed_path = dir.join(SEED_FILE);
    let mut f = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&seed_path)
        .map_err(|e| format!("{}: {e}", seed_path.display()))?;
    f.write_all(&seed[..])
        .and_then(|_| f.sync_all())
        .map_err(|e| format!("{}: {e}", seed_path.display()))?;
    let hex = to_hex(pk.as_bytes());
    let pub_path = dir.join(PUBLIC_FILE);
    OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o644)
        .open(&pub_path)
        .and_then(|mut p| p.write_all(format!("{hex}\n").as_bytes()))
        .map_err(|e| format!("{}: {e}", pub_path.display()))?;
    Ok(hex)
}

#[cfg(not(unix))]
fn keygen(_dir: &Path) -> Result<String, String> {
    Err("keygen writes the secret with a unix file mode; run it on the server".into())
}

fn load(dir: &Path) -> Result<(PublicKey, SecretKey), String> {
    let path = dir.join(SEED_FILE);
    let bytes =
        Zeroizing::new(std::fs::read(&path).map_err(|e| format!("{}: {e}", path.display()))?);
    let mut seed = Zeroizing::new([0u8; KEYPAIR_SEED_SIZE]);
    if bytes.len() != KEYPAIR_SEED_SIZE {
        return Err(format!(
            "{}: not a {KEYPAIR_SEED_SIZE}-byte seed",
            path.display()
        ));
    }
    seed.copy_from_slice(&bytes);
    keys_from(&seed)
}

fn field<'a>(v: &'a Value, k: &str) -> Result<&'a Value, String> {
    v.get(k)
        .filter(|x| !x.is_null())
        .ok_or_else(|| format!("missing field {k}"))
}

fn sign(dir: &Path) -> Result<String, String> {
    let (_, sk) = load(dir)?;
    let mut input = String::new();
    std::io::stdin()
        .take(STDIN_MAX)
        .read_to_string(&mut input)
        .map_err(|e| format!("stdin: {e}"))?;
    let v: Value = serde_json::from_str(&input).map_err(|e| format!("stdin is not JSON: {e}"))?;
    let e164 = field(&v, "e164")?
        .as_str()
        .filter(|s| valid_e164(s))
        .ok_or("e164 is not +digits")?
        .to_owned();
    let subject = field(&v, "subject")?
        .as_str()
        .and_then(hex_array::<32>)
        .ok_or("subject is not 64 hex digits")?;
    let channel = field(&v, "channel")?
        .as_u64()
        .and_then(|c| u8::try_from(c).ok())
        .filter(|c| *c == CHANNEL_BOT || *c == CHANNEL_MESSAGE)
        .ok_or("channel is not 1 or 2")?;
    let at_ms = field(&v, "at_ms")?
        .as_u64()
        .ok_or("at_ms is not an unsigned integer")?;
    let attest = Attest {
        channel,
        at_ms,
        subject,
        e164,
    };
    let bytes = attest.seal(&sk).map_err(|e| e.to_string())?;
    Ok(to_hex(&bytes))
}

fn sign_email(dir: &Path) -> Result<String, String> {
    let (_, sk) = load(dir)?;
    let mut input = String::new();
    std::io::stdin()
        .take(STDIN_MAX)
        .read_to_string(&mut input)
        .map_err(|e| format!("stdin: {e}"))?;
    let v: Value = serde_json::from_str(&input).map_err(|e| format!("stdin is not JSON: {e}"))?;
    let email = field(&v, "email")?
        .as_str()
        .filter(|s| valid_email(s))
        .ok_or("email is not an address")?
        .to_owned();
    let subject = field(&v, "subject")?
        .as_str()
        .and_then(hex_array::<32>)
        .ok_or("subject is not 64 hex digits")?;
    let at_ms = field(&v, "at_ms")?
        .as_u64()
        .ok_or("at_ms is not an unsigned integer")?;
    let attest = EmailAttest {
        at_ms,
        subject,
        email,
    };
    let bytes = attest.seal(&sk).map_err(|e| e.to_string())?;
    Ok(to_hex(&bytes))
}

fn pubkey(dir: &Path) -> Result<String, String> {
    let (pk, _) = load(dir)?;
    Ok(to_hex(pk.as_bytes()))
}

fn usage() -> ExitCode {
    eprintln!("usage: mt-business-attest keygen DIR | sign DIR | sign-email DIR | pubkey DIR");
    ExitCode::from(2)
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let (Some(cmd), Some(dir), None) = (args.get(1), args.get(2), args.get(3)) else {
        return usage();
    };
    let dir = PathBuf::from(dir);
    let out = match cmd.as_str() {
        "keygen" => keygen(&dir),
        "sign" => sign(&dir),
        "sign-email" => sign_email(&dir),
        "pubkey" => pubkey(&dir),
        _ => return usage(),
    };
    match out {
        Ok(line) => {
            println!("{line}");
            ExitCode::SUCCESS
        },
        Err(e) => {
            eprintln!("mt-business-attest: {e}");
            ExitCode::FAILURE
        },
    }
}
