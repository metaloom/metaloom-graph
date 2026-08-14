//! The adjacency edge codec: delta plus varint over packed 8-byte entries.
//!
//! Output must be byte-for-byte identical to `io.metaloom.graph.core.store.adj.EdgeCodec`, which remains the
//! reference. The differential test in `NativeCodecTest` is what enforces that.

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
        // A group of zero entries is malformed: the encoder emits a group only when it has something to put in it.
        // Rejecting it is not pedantry — the first entry below is written before the `1..count` loop, so a zero count
        // still produced one entry, and with a full destination that was an out-of-bounds index. The second defect
        // `cargo fuzz run edges_decode` found, and the second one the Java decoder shared exactly.
        if count == 0 {
            return Err(());
        }
        // checked_add, not `+`: `count` comes off a page and can be anything a varint can hold. The sum overflows
        // usize for a large enough one, and an overflowed sum compares as small — so the bound below would pass and
        // the loop would run against a destination it does not fit.
        match written.checked_add(count) {
            Some(total) if total <= dst.len() => {}
            _ => return Err(()),
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
            // Found by `cargo fuzz run edges_decode`, within seconds of the target existing.
            //
            // This was `current += delta`. Deltas are read from the page and are therefore arbitrary, so the sum
            // overflows for a large one — and in the release build that ships as the shared object, overflow checks
            // are off, so it wrapped. A wrapped value is usually small, which means it passed the range check below
            // and the decoder emitted a **plausible wrong neighbour id** rather than reporting corruption. The one
            // failure mode a corrupt block must never have.
            //
            // It was invisible to the differential test against Java, because the Java decoder wrapped in exactly the
            // same way and the two agreed on the wrong answer. Both are fixed.
            current = match current.checked_add(delta) {
                Some(sum) => sum,
                None => return Err(()),
            };
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
