# 02 — Transit network

A small metro network with four lines and weighted track segments.
**27 nodes / 64 relationships.**

## Dump style

Many small, independent statements: one `CREATE` per node, one `MATCH … CREATE` per
track segment (each carries its own travel time and distance, which is what justifies a
statement per edge), and one grouped `MATCH … WHERE x IN [...] CREATE` per line and per
fare zone. Verbose, but every statement can be moved, re-ordered or replayed on its own.

## Schema

| Label | Count | Key properties |
|---|---|---|
| `Station` | 20 | `name`, `code`, `platforms`, `stepFree` |
| `Line` | 4 | `name`, `colour`, `opened` |
| `Zone` | 3 | `name`, `fare` |

| Type | Count | Pattern | Properties |
|---|---|---|---|
| `CONNECTS` | 20 | `(:Station)-->(:Station)` | `line`, `minutes`, `distanceKm` |
| `SERVED_BY` | 24 | `(:Station)-->(:Line)` | — |
| `IN_ZONE` | 20 | `(:Station)-->(:Zone)` | — |

**`CONNECTS` is stored once per adjacent pair**, in the direction the line runs. Traverse
it undirected (`-[:CONNECTS]-`, not `->`) — the shortest-path and neighbourhood queries in
the catalogue rely on that.

## Topology

```
Red     Airport - North Gate - Museum - Central - Market Square - South Gate - Stadium
Blue    West End - Old Town - Central - Cathedral - East Park - Tech Park
Green   Harbour - Riverside - Market Square - University - Botanic Garden - Zoo
Yellow  Hospital - Museum - Observatory - Fair Ground - University
```

Interchanges: **Central** (Red/Blue), **Museum** (Red/Yellow), **Market Square**
(Red/Green), **University** (Green/Yellow). The two interchange chains make
`Airport → Zoo` reachable by two different 7-segment routes of equal length, so `t-07`
has a stable expected count whichever one `shortestPath` picks.

## Queries

`t-01` … `t-07` in [`../../queries.csv`](../../queries.csv) — including an aggregation
that finds interchanges (`t-04`), an undirected two-hop neighbourhood (`t-06`) and a
`shortestPath` measured with `size(nodes(p))` / `size(relationships(p))` (`t-07`).
