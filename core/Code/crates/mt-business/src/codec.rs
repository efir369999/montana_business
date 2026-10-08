use mt_codec::{write_bytes, write_u16};

pub type Short<T> = Result<T, &'static str>;

pub struct Reader<'a> {
    buf: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Self { buf, at: 0 }
    }

    pub fn take(&mut self, n: usize) -> Short<&'a [u8]> {
        let end = self.at.checked_add(n).ok_or("length overflow")?;
        let s = self.buf.get(self.at..end).ok_or("truncated")?;
        self.at = end;
        Ok(s)
    }

    pub fn array<const N: usize>(&mut self) -> Short<[u8; N]> {
        let mut out = [0u8; N];
        out.copy_from_slice(self.take(N)?);
        Ok(out)
    }

    pub fn u8(&mut self) -> Short<u8> {
        let [b] = self.array::<1>()?;
        Ok(b)
    }

    pub fn u16(&mut self) -> Short<u16> {
        Ok(u16::from_le_bytes(self.array()?))
    }

    pub fn u32(&mut self) -> Short<u32> {
        Ok(u32::from_le_bytes(self.array()?))
    }

    pub fn u64(&mut self) -> Short<u64> {
        Ok(u64::from_le_bytes(self.array()?))
    }

    // Canonical: only 0 and 1, so one flag has one encoding and one record id.
    pub fn flag(&mut self) -> Short<bool> {
        match self.u8()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err("flag is not 0 or 1"),
        }
    }

    pub fn string(&mut self, max: usize) -> Short<String> {
        let n = usize::from(self.u16()?);
        if n > max {
            return Err("string too long");
        }
        let s = self.take(n)?;
        std::str::from_utf8(s)
            .map(str::to_owned)
            .map_err(|_| "string is not UTF-8")
    }

    pub fn done(&self) -> bool {
        self.at == self.buf.len()
    }
}

pub fn put_string(buf: &mut Vec<u8>, s: &str, max: usize) -> Short<()> {
    if s.len() > max {
        return Err("string too long");
    }
    let n = u16::try_from(s.len()).map_err(|_| "string too long")?;
    write_u16(buf, n);
    write_bytes(buf, s.as_bytes());
    Ok(())
}

pub fn put_flag(buf: &mut Vec<u8>, v: bool) {
    buf.push(u8::from(v));
}

const HEX: &[u8; 16] = b"0123456789abcdef";

pub fn to_hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len().saturating_mul(2));
    for b in bytes {
        s.push(char::from(HEX[usize::from(b >> 4)]));
        s.push(char::from(HEX[usize::from(b & 0x0f)]));
    }
    s
}

fn nibble(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

pub fn from_hex(s: &str) -> Option<Vec<u8>> {
    let b = s.as_bytes();
    if b.len() % 2 != 0 {
        return None;
    }
    b.chunks_exact(2)
        .map(|p| Some((nibble(p[0])? << 4) | nibble(p[1])?))
        .collect()
}

pub fn hex_array<const N: usize>(s: &str) -> Option<[u8; N]> {
    let v = from_hex(s)?;
    if v.len() != N {
        return None;
    }
    let mut out = [0u8; N];
    out.copy_from_slice(&v);
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_roundtrip_and_rejects() {
        let v = [0x00u8, 0x0f, 0xa5, 0xff, 0x10];
        assert_eq!(to_hex(&v), "000fa5ff10");
        assert_eq!(from_hex("000FA5ff10").as_deref(), Some(&v[..]));
        assert_eq!(from_hex("abc"), None);
        assert_eq!(from_hex("zz"), None);
        assert_eq!(hex_array::<2>("a5ff"), Some([0xa5, 0xff]));
        assert_eq!(hex_array::<2>("a5"), None);
    }

    #[test]
    fn reader_reads_little_endian_and_stops_at_the_end() {
        let bytes = [0x01u8, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09];
        let mut r = Reader::new(&bytes);
        assert_eq!(r.u8(), Ok(0x01));
        assert_eq!(r.u64(), Ok(0x0908_0706_0504_0302));
        assert!(r.done());
        assert_eq!(r.u8(), Err("truncated"));
    }

    #[test]
    fn flag_is_canonical() {
        assert_eq!(Reader::new(&[1]).flag(), Ok(true));
        assert_eq!(Reader::new(&[0]).flag(), Ok(false));
        assert!(Reader::new(&[2]).flag().is_err());
    }

    #[test]
    fn string_limits_and_utf8() {
        let mut buf = Vec::new();
        put_string(&mut buf, "Анна", 8).expect("fits");
        assert_eq!(buf, [8, 0, 0xd0, 0x90, 0xd0, 0xbd, 0xd0, 0xbd, 0xd0, 0xb0]);
        assert_eq!(Reader::new(&buf).string(8).as_deref(), Ok("Анна"));
        assert!(Reader::new(&buf).string(7).is_err());
        assert!(put_string(&mut Vec::new(), "Анна", 7).is_err());
        assert!(Reader::new(&[1, 0, 0xff]).string(8).is_err());
    }
}
