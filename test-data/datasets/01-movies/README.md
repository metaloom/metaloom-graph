# 01 — Movies

A small, hand-written film graph. **29 nodes / 52 relationships.**

## Dump style

One single `CREATE` statement. Every node is bound to a variable up front and the
relationships are appended to the same statement as pattern chains referring back to
those variables — the most compact form a openCypher dump can take, but it only scales
as far as one statement stays readable.

## Schema

| Label | Count | Key properties |
|---|---|---|
| `Person` | 14 | `name`, `born`, `country` |
| `Movie` | 8 | `title`, `released`, `runtime`, `rating` |
| `Genre` | 4 | `name` |
| `Studio` | 3 | `name`, `founded`, `city` |

| Type | Count | Pattern | Properties |
|---|---|---|---|
| `ACTED_IN` | 21 | `(:Person)-->(:Movie)` | `roles` (list of string) |
| `DIRECTED` | 11 | `(:Person)-->(:Movie)` | — |
| `IN_GENRE` | 12 | `(:Movie)-->(:Genre)` | — |
| `PRODUCED_BY` | 8 | `(:Movie)-->(:Studio)` | `budget` (million USD) |

Five people are directors only (`Lana Wachowski`, `Lilly Wachowski`, `Nora Ephron`,
`Rob Reiner`, `Cameron Crowe`), so `ACTED_IN` touches only 9 of the 14 `Person` nodes.

## Queries

`m-01` … `m-07` in [`../../queries.csv`](../../queries.csv) — totals, label projection,
single hop, a filter on `released`, a three-label path, a studio-anchored two hop and a
genre lookup.
