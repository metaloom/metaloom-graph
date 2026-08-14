//! CRC32C and the adjacency edge codec.
//!
//! Both must produce output that is byte-for-byte identical to the Java implementations in
//! `io.metaloom.graph.core.util.Crc` and `io.metaloom.graph.core.store.adj.EdgeCodec`, which remain the reference.
//! The differential test in `NativeCodecTest` is what enforces that.

/// The CRC32C (Castagnoli) polynomial, reflected.
const POLY: u32 = 0x82F6_3B78;

pub fn crc32c(data: &[u8], seed: u32) -> u32 {
    let mut crc = !seed;
    for byte in data {
        crc ^= *byte as u32;
        for _ in 0..8 {
            let mask = (crc & 1).wrapping_neg();
            crc = (crc >> 1) ^ (POLY & mask);
        }
    }
    !crc
}

// ---------------------------------------------------------------- varint

fn varint_len(value: u64) -> usize {
    let mut n = 1;
    let mut v = value >> 7;
    while v != 0 {
        n += 1;
        v >>= 7;
    }
    n
}

fn varint_write(dst: &mut [u8], mut offset: usize, mut value: u64) -> usize {
    while value & !0x7F != 0 {
        dst[offset] = ((value & 0x7F) as u8) | 0x80;
        offset += 1;
        value >>= 7;
    }
    dst[offset] = value as u8;
    offset + 1
}

fn varint_read(src: &[u8], mut offset: usize) -> Result<(u64, usize), ()> {
    let mut result: u64 = 0;
    let mut shift = 0;
    loop {
        if offset >= src.len() || shift > 63 {
            return Err(());
        }
        let byte = src[offset];
        offset += 1;
        result |= ((byte & 0x7F) as u64) << shift;
        if byte & 0x80 == 0 {
            return Ok((result, offset));
        }
        shift += 7;
    }
}

// ---------------------------------------------------------------- edges

const LOCATION_MASK: u64 = (1 << 48) - 1;

fn group_key(edge: u64) -> u64 {
    edge >> 48
}

fn location(edge: u64) -> u64 {
    edge & LOCATION_MASK
}

/// Encodes into `dst`. On success returns the bytes written; on overflow returns the capacity needed.
pub fn encode_edges(entries: &[u64], dst: &mut [u8]) -> Result<usize, usize> {
    if entries.is_empty() {
        if dst.is_empty() {
            return Err(1);
        }
        dst[0] = 0;
        return Ok(1);
    }
    let mut work = entries.to_vec();
    // Sorting the raw packed value orders by dir, then type, then location, exactly as the Java side does.
    work.sort_unstable();

    let mut groups = 1usize;
    for i in 1..work.len() {
        if group_key(work[i]) != group_key(work[i - 1]) {
            groups += 1;
        }
    }

    let upper_bound = varint_len(groups as u64) + work.len() * 9 + groups * 20;
    if dst.len() < upper_bound {
        return Err(upper_bound);
    }

    let mut pos = varint_write(dst, 0, groups as u64);
    let mut i = 0usize;
    while i < work.len() {
        let key = group_key(work[i]);
        let mut end = i + 1;
        while end < work.len() && group_key(work[end]) == key {
            end += 1;
        }
        pos = varint_write(dst, pos, key);
        pos = varint_write(dst, pos, (end - i) as u64);
        let mut previous = location(work[i]);
        pos = varint_write(dst, pos, previous);
        for entry in work.iter().take(end).skip(i + 1) {
            let current = location(*entry);
            pos = varint_write(dst, pos, current - previous);
            previous = current;
        }
        i = end;
    }
    Ok(pos)
}

/// Decodes into `dst`, returning the number of entries written.
pub fn decode_edges(src: &[u8], dst: &mut [u64]) -> Result<usize, ()> {
    let (groups, mut pos) = varint_read(src, 0)?;
    let mut written = 0usize;
    for _ in 0..groups {
        let (key, next) = varint_read(src, pos)?;
        pos = next;
        let (count, next) = varint_read(src, pos)?;
        pos = next;
        let count = count as usize;
        if written + count > dst.len() {
            return Err(());
        }
        let (first, next) = varint_read(src, pos)?;
        pos = next;
        let mut current = first;
        if current > LOCATION_MASK {
            return Err(());
        }
        dst[written] = (key << 48) | current;
        written += 1;
        for _ in 1..count {
            let (delta, next) = varint_read(src, pos)?;
            pos = next;
            current += delta;
            if current > LOCATION_MASK {
                return Err(());
            }
            dst[written] = (key << 48) | current;
            written += 1;
        }
    }
    Ok(written)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn edges_round_trip() {
        let entries: Vec<u64> = (0..500).map(|i| (3u64 << 48) | (1000 + i * 7)).collect();
        let mut buffer = vec![0u8; 16 * 1024];
        let written = encode_edges(&entries, &mut buffer).expect("encode");
        let mut out = vec![0u64; entries.len()];
        let decoded = decode_edges(&buffer[..written], &mut out).expect("decode");
        assert_eq!(decoded, entries.len());
        let mut expected = entries.clone();
        expected.sort_unstable();
        assert_eq!(out, expected);
    }

    #[test]
    fn truncated_input_is_rejected() {
        let entries = vec![(1u64 << 48) | 5, (1u64 << 48) | 9];
        let mut buffer = vec![0u8; 1024];
        let written = encode_edges(&entries, &mut buffer).expect("encode");
        let mut out = vec![0u64; entries.len()];
        assert!(decode_edges(&buffer[..written - 1], &mut out).is_err());
    }
}
