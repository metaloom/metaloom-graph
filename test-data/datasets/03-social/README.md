# 03 — Social network

A generated follow/post/like graph. **188 nodes / 570 relationships.**

## Dump style

Data driven. Nodes come from `UNWIND range(...)` and from `UNWIND` over a list of maps;
every edge set is produced by deterministic modular arithmetic over the node ids. Nothing
is hand-listed, the graph is reproducible byte for byte, and every expected count in the
catalogue is derivable on paper rather than by counting lines.

## Schema

| Label | Count | Key properties |
|---|---|---|
| `User` | 60 (`userId` 0..59) | `handle`, `displayName`, `joinedYear`, `reputation`, `verified` |
| `Post` | 120 (`postId` 0..119) | `title`, `words`, `language`, `pinned` |
| `Tag` | 8 (`tagIndex` 0..7) | `name`, `topic` |

| Type | Count | Rule |
|---|---|---|
| `FOLLOWS` | 120 | user `u` follows `(7u+3) % 60` and `(13u+11) % 60` |
| `POSTED` | 120 | post `p` is authored by user `p % 60` — 2 posts each |
| `TAGGED` | 150 | post `p` carries tag `p % 8`; posts with `p % 4 = 0` also carry `(3p+1) % 8` |
| `LIKES` | 180 | user `u` likes posts `(11u + 17k) % 120` for `k` in 0..2 |

The multipliers are chosen so the generators never degenerate:

- `7u+3 ≡ u (mod 60)` and `13u+11 ≡ u (mod 60)` have no solutions → **no self-follows**.
- `7u+3 ≡ 13u+11 (mod 60)` has no solutions → **the two follow targets are always distinct**,
  hence exactly 2 edges per user with no dedup.
- `3p+1 ≡ p (mod 8)` has no solutions → **a post's second tag is never its first tag**.
- The three like offsets differ by 17 and 34, neither ≡ 0 (mod 120) → **3 distinct likes per user**.

Consequences worth knowing before writing new queries: exactly 6 unordered pairs of users
follow each other back; the tags `cypher` and `music` land on 30 posts each while the other
six land on 15; likes split the posts evenly into 60 posts liked twice and 60 liked once.

## Queries

`s-01` … `s-08` in [`../../queries.csv`](../../queries.csv). `s-05` and `s-08` traverse
`User → User`, where a naive `count(DISTINCT a) + count(DISTINCT b)` would count the same
node twice — they use the `collect(DISTINCT …) + collect(DISTINCT …)` / `UNWIND` /
`count(DISTINCT …)` form instead, which counts the union.
