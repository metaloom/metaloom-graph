1. The compaction/writer interleave defect — highest severity, and I learned something about it today.
PROGRESS.md:726 records CompactionTest.testCompactionInterleavesWithForegroundWrites failing 6/12 with a NODE page whose checksum disagrees with its contents — i.e. possible silent corruption.

I tried to attribute it, expecting my verify() writer-lock fix had closed it: the symptom is exactly what a verify() racing a writer used to produce. It hadn't. It no longer reproduces anywhere — 0/8 at 0970e6b (the very commit the 6/12 was measured on), 0/7 interleaved at the pre-fix commit, 0/7 interleaved and 0/12 sequential at HEAD. So it's timing-sensitive and dormant on this 24-core host, not fixed. I've recorded that in the defect entry, because the trap here is real: a green run at HEAD would otherwise read as a fix. Settling it needs a machine that reproduces it.

2. SoakTest is still red. Re-ran it just now: 346 → 606 B per live element against a 1.5x bound. mvn test -Pslow does not pass. Pre-existing (attributed to before my work), documented, unfixed. It's a space-reclamation problem, not concurrency, but it's the leak-and-growth guard.

3. Concurrent writers — the architectural gap. Writers are serialised by one lock. TRANSACTIONS.md §10 lists LockTable, UndoStore, the xmin scheme and SSI/SERIALIZABLE as unbuilt, each correctly marked "nothing to detect" while writers are serialised. They all unblock together, and the blocker is that page overlays resolve two writers on one page by last-writer-wins — a rewrite of the write path, not an addition.

4. Durability evidence that has never actually been executed. The 12-hour soak, a week of nightly crash loops, and the manual power-loss test. The last one specifically matters: SyncModeDurabilityTest cannot separate GROUP_COMMIT/ASYNC from FSYNC_ON_COMMIT, because an in-process abandon() leaves the OS page cache intact — only real power loss distinguishes them. Right now that difference is asserted nowhere.

5. CI has never run. PROGRESS.md:439 — the workflow exists, no runner is attached. Concurrency tests are the ones that benefit most from repeated automated execution, and item 1 is precisely a defect that only shows up across many runs on varied hardware. This is probably the highest-leverage item on the list for the money.

6. Blocked or deliberately closed. The jcstress harness (not in the offline Maven repo) and the foreground p99 latency budget during compaction (blocked on item 1).