# 04 — Supply chain

A generated parts/products/warehouse/shipment graph. **136 nodes / 352 relationships.**

## Dump style

`MERGE` only, therefore **idempotent**: importing the file twice must not change a single
count. That is enforced two ways —

- node identity is the business key inside the `MERGE` pattern (`{partId: …}`), and every
  other property is written with `ON CREATE SET`, so a re-run neither duplicates nor
  rewrites anything;
- relationships are `MERGE`d on the bare pattern with no inline properties, because inline
  properties would make the second run miss the existing edge and create a parallel one.

`verify.py` imports this dataset a **second** time and re-checks the totals row (reported as
`c-01r`), so a regression in that discipline fails the run.

## Schema

| Label | Count | Key properties |
|---|---|---|
| `Supplier` | 12 (`supplierId` 0..11) | `name`, `country`, `rating`, `audited` |
| `Part` | 60 (`partId` 0..59) | `sku`, `material`, `weightG`, `critical` |
| `Product` | 20 (`productId` 0..19) | `name`, `priceCents`, `launched` |
| `Warehouse` | 8 (`warehouseId` 0..7) | `city`, `capacity`, `automated` |
| `Shipment` | 30 (`shipmentId` 0..29) | `reference`, `dispatched`, `pallets` |
| `Category` | 6 (`categoryId` 0..5) | `name` |

| Type | Count | Rule | Properties |
|---|---|---|---|
| `SUPPLIES` | 72 | supplier `p % 12` → part `p`; parts with `p % 5 = 0` get the second source `(7p+1) % 12` | `leadTimeDays`, `primary` |
| `CONTAINS` | 80 | product `q` → parts `(3q+k) % 60`, `k` in 0..3 | `quantity` |
| `IN_CATEGORY` | 20 | product `q` → category `q % 6` | — |
| `STORED_IN` | 60 | part `p` → warehouse `p % 8` | `stock` |
| `SHIPPED_FROM` | 30 | shipment `s` → warehouse `s % 8` | — |
| `SHIPPED_TO` | 30 | shipment `s` → warehouse `(s+3) % 8` | — |
| `CARRIES` | 60 | shipment `s` → parts `(2s+k) % 60`, `k` in 0..1 | `units` |

`6p+1 ≡ 0 (mod 12)` has no solution, so a dual-sourced part never gets the same supplier
twice — the 12 second sources are 12 genuinely extra edges. `s % 8 ≠ (s+3) % 8` always, so
no shipment ships to the warehouse it left. `CONTAINS` covers all 60 parts (the products'
windows of four tile `0..60`, and `60 % 60 = 0`).

## Queries

`c-01` … `c-07` in [`../../queries.csv`](../../queries.csv), plus the automatic
re-import check. `c-06` is the deepest path in the corpus: category → product → part →
warehouse.
