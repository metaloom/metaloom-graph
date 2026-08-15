//! io_uring: batched, asynchronous, high-queue-depth I/O.
//!
//! Capability N1 of spec/NATIVE_FFM.md, and the only one on that list the JVM genuinely cannot approach. A page fault
//! and a `FileChannel.read` are both synchronous: a traversal that needs eight pages issues eight round trips and
//! waits for each in turn. A ring lets it say "fetch these eight" once and collect them as they land, which on an NVMe
//! device is the difference between eight times the latency and roughly one.
//!
//! The surface is deliberately small and owns no policy: create a ring, submit reads, submit fsyncs, reap
//! completions. Everything about *which* pages to fetch stays in Java.
//!
//! # Safety
//!
//! Every buffer handed to `submit_read` must stay alive and unmoved until its completion is reaped — the kernel
//! writes into it asynchronously. On the Java side that means an `Arena`-allocated `MemorySegment` held across the
//! call, which is exactly why the Java binding allocates through the engine rather than from the heap.

use io_uring::{opcode, types, IoUring};
use std::os::unix::io::RawFd;

/// A ring plus the state a caller needs to keep across submissions.
pub struct Ring {
    inner: IoUring,
    /// Submitted but not yet reaped. Kept so `submit` can tell the kernel how many are pending.
    in_flight: u32,
}

/// One completion, mirrored on the Java side as a 16-byte `#[repr(C)]` struct.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct Cqe {
    pub user_data: u64,
    pub result: i32,
    pub flags: u32,
}

impl Ring {
    pub fn new(depth: u32) -> std::io::Result<Self> {
        let depth = depth.clamp(1, 4096).next_power_of_two();
        Ok(Ring {
            inner: IoUring::new(depth)?,
            in_flight: 0,
        })
    }

    pub fn in_flight(&self) -> u32 {
        self.in_flight
    }

    /// Queues a read. Returns false when the submission queue is full, which is a signal to submit and reap, not an
    /// error.
    ///
    /// # Safety
    ///
    /// `buf` must point to at least `len` writable bytes that outlive the completion.
    pub unsafe fn submit_read(
        &mut self,
        fd: RawFd,
        buf: *mut u8,
        len: u32,
        offset: u64,
        user_data: u64,
    ) -> bool {
        let entry = opcode::Read::new(types::Fd(fd), buf, len)
            .offset(offset)
            .build()
            .user_data(user_data);
        // SAFETY: the caller guarantees the buffer outlives the completion, which is the whole contract of this
        // function and the reason it is unsafe.
        match unsafe { self.inner.submission().push(&entry) } {
            Ok(()) => {
                self.in_flight += 1;
                true
            }
            Err(_) => false,
        }
    }

    /// Queues a write. Same buffer lifetime rule as `submit_read`.
    ///
    /// # Safety
    ///
    /// `buf` must point to at least `len` readable bytes that outlive the completion.
    pub unsafe fn submit_write(
        &mut self,
        fd: RawFd,
        buf: *const u8,
        len: u32,
        offset: u64,
        user_data: u64,
    ) -> bool {
        let entry = opcode::Write::new(types::Fd(fd), buf, len)
            .offset(offset)
            .build()
            .user_data(user_data);
        // SAFETY: as above.
        match unsafe { self.inner.submission().push(&entry) } {
            Ok(()) => {
                self.in_flight += 1;
                true
            }
            Err(_) => false,
        }
    }

    pub fn submit_fsync(&mut self, fd: RawFd, user_data: u64) -> bool {
        let entry = opcode::Fsync::new(types::Fd(fd))
            .build()
            .user_data(user_data);
        // SAFETY: an fsync entry references no user buffer, so there is nothing for the kernel to outlive.
        match unsafe { self.inner.submission().push(&entry) } {
            Ok(()) => {
                self.in_flight += 1;
                true
            }
            Err(_) => false,
        }
    }

    /// Hands everything queued to the kernel. Returns the number of entries accepted.
    pub fn submit(&mut self) -> std::io::Result<usize> {
        self.inner.submit()
    }

    /// Submits, then blocks until at least `min_complete` entries have finished.
    pub fn submit_and_wait(&mut self, min_complete: usize) -> std::io::Result<usize> {
        self.inner.submit_and_wait(min_complete)
    }

    /// Collects up to `max` completions into `out`. Never blocks.
    pub fn reap(&mut self, out: &mut [Cqe], max: usize) -> usize {
        let mut written = 0;
        let limit = max.min(out.len());
        let mut queue = self.inner.completion();
        queue.sync();
        while written < limit {
            match queue.next() {
                Some(entry) => {
                    out[written] = Cqe {
                        user_data: entry.user_data(),
                        result: entry.result(),
                        flags: entry.flags(),
                    };
                    written += 1;
                }
                None => break,
            }
        }
        self.in_flight -= written.min(self.in_flight as usize) as u32;
        written
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::os::unix::io::AsRawFd;

    #[test]
    // Miri interprets Rust; it has no kernel, so io_uring_setup is an unsupported operation rather than a failure it
    // could report. Every test in this module needs a real ring, which is exactly the part of the crate miri cannot
    // reach — and exactly why the raw-pointer entry points are covered separately in lib.rs, where it can.
    #[cfg_attr(miri, ignore = "io_uring needs a real kernel")]
    fn reads_a_file_through_the_ring() {
        let mut file = tempfile("read");
        let payload: Vec<u8> = (0..8192u32).map(|i| (i % 251) as u8).collect();
        file.write_all(&payload).unwrap();
        file.sync_all().unwrap();

        let mut ring = match Ring::new(8) {
            Ok(ring) => ring,
            // A kernel or container without io_uring is a legitimate environment; the Java side falls back.
            Err(_) => return,
        };
        let mut buffers = vec![vec![0u8; 4096]; 2];
        for (i, buffer) in buffers.iter_mut().enumerate() {
            let ok = unsafe {
                ring.submit_read(
                    file.as_raw_fd(),
                    buffer.as_mut_ptr(),
                    4096,
                    (i * 4096) as u64,
                    i as u64,
                )
            };
            assert!(
                ok,
                "the submission queue must accept two entries at depth 8"
            );
        }
        ring.submit_and_wait(2).unwrap();

        let mut completions = [Cqe {
            user_data: 0,
            result: 0,
            flags: 0,
        }; 4];
        let reaped = ring.reap(&mut completions, 4);
        assert_eq!(reaped, 2);
        for cqe in completions.iter().take(2) {
            assert_eq!(cqe.result, 4096, "each read should return a full block");
        }
        assert_eq!(ring.in_flight(), 0);

        // And the bytes are the right ones, in the right order.
        for (i, buffer) in buffers.iter().enumerate() {
            assert_eq!(&buffer[..], &payload[i * 4096..(i + 1) * 4096]);
        }
    }

    #[test]
    #[cfg_attr(miri, ignore = "io_uring needs a real kernel")]
    fn a_full_submission_queue_is_reported_rather_than_panicking() {
        let mut ring = match Ring::new(1) {
            Ok(ring) => ring,
            Err(_) => return,
        };
        let file = tempfile("overflow");
        let mut buffer = vec![0u8; 512];
        let mut accepted = 0;
        for i in 0..8 {
            if unsafe { ring.submit_read(file.as_raw_fd(), buffer.as_mut_ptr(), 512, 0, i) } {
                accepted += 1;
            }
        }
        assert!(
            (1..8).contains(&accepted),
            "a depth-1 ring must refuse the overflow, accepted {accepted}"
        );

        // Drain before `buffer` goes out of scope. An in-flight read owns its buffer until its completion is reaped,
        // and dropping the ring does not recall an operation the kernel has already accepted — so returning here
        // would leave the kernel free to write into freed memory. The rule this test exists beside is the same one
        // the Java binding follows by allocating from an Arena held across the call.
        ring.submit_and_wait(accepted).unwrap();
        let mut completions = [Cqe {
            user_data: 0,
            result: 0,
            flags: 0,
        }; 8];
        while ring.in_flight() > 0 {
            if ring.reap(&mut completions, 8) == 0 {
                ring.submit_and_wait(ring.in_flight() as usize).unwrap();
            }
        }
    }

    /// A path per test, not per process. Cargo runs the tests in this module on threads of one process, so a name
    /// built from the pid alone is shared — and this module opens with `truncate`, so one test emptied the file the
    /// other was mid-way through reading. It failed about one run in five, always as a read returning 0 bytes.
    fn tempfile(tag: &str) -> std::fs::File {
        let path = std::env::temp_dir().join(format!("mlg-ring-{}-{tag}.bin", std::process::id()));
        std::fs::OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .truncate(true)
            .open(path)
            .unwrap()
    }
}
