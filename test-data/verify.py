#!/usr/bin/env python3
"""Import every dataset into a running Neo4j and check queries.csv against it.

Neo4j Community serves a single user database, so the datasets are processed
sequentially: wipe -> import the dump -> run that dataset's queries.

The dumps and the queries are plain openCypher; everything Neo4j specific
(container handling, cypher-shell invocation) lives here and in
run-verification.sh, never in the .cypher files.

Invoked by run-verification.sh; standalone usage:
    ./verify.py --container cypher-data-neo4j --password <pw>
"""

import argparse
import csv
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def shell(args, cypher, timeout=300):
    """Run cypher-shell inside the container, feeding it the statements on
    stdin. Piping rather than mounting keeps this working when the docker
    daemon does not share a filesystem with us."""
    cmd = [
        "docker", "exec", "-i", args.container,
        "cypher-shell", "-u", args.user, "-p", args.password,
        "--non-interactive", "--fail-fast", "--format", "plain",
    ]
    proc = subprocess.run(
        cmd, input=cypher, capture_output=True, text=True, timeout=timeout
    )
    return proc.returncode, proc.stdout, proc.stderr


def read_counts(stdout):
    """Parse the two-column 'nodes, rels' result of a catalogue query."""
    rows = [r for r in csv.reader(stdout.splitlines()) if r]
    if len(rows) < 2:
        raise ValueError("query returned no rows")
    header = [c.strip() for c in rows[0]]
    if header != ["nodes", "rels"]:
        raise ValueError("expected columns 'nodes, rels' but got %r" % (header,))
    values = [c.strip() for c in rows[1]]
    if len(values) != 2:
        raise ValueError("expected two values but got %r" % (values,))
    return int(values[0]), int(values[1])


def load_catalogue(path):
    with open(path, newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    for row in rows:
        row["expected_nodes"] = int(row["expected_nodes"])
        row["expected_relationships"] = int(row["expected_relationships"])
    return rows


def wipe(args):
    rc, out, err = shell(args, cypher="MATCH (n) DETACH DELETE n;\n")
    if rc != 0:
        raise RuntimeError("wipe failed: %s%s" % (out, err))


def import_dump(args, dataset):
    path = os.path.join(HERE, "datasets", dataset, "data.cypher")
    with open(path, encoding="utf-8") as fh:
        dump = fh.read()
    rc, out, err = shell(args, cypher=dump)
    if rc != 0:
        raise RuntimeError("import of %s failed: %s%s" % (dataset, out, err))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--container", default="cypher-data-neo4j")
    parser.add_argument("--user", default="neo4j")
    parser.add_argument("--password", required=True)
    parser.add_argument("--csv", default=os.path.join(HERE, "queries.csv"))
    parser.add_argument("--results", default=os.path.join(HERE, "verification-results.csv"))
    parser.add_argument("--dataset", action="append", default=None,
                        help="only verify this dataset (repeatable)")
    args = parser.parse_args()

    catalogue = load_catalogue(args.csv)
    datasets = sorted({row["dataset"] for row in catalogue})
    if args.dataset:
        unknown = set(args.dataset) - set(datasets)
        if unknown:
            print("unknown dataset(s): %s" % ", ".join(sorted(unknown)), file=sys.stderr)
            return 2
        datasets = [d for d in datasets if d in args.dataset]

    results = []
    failures = 0

    for dataset in datasets:
        print("\n=== %s ===" % dataset, flush=True)
        wipe(args)
        import_dump(args, dataset)
        print("imported datasets/%s/data.cypher" % dataset, flush=True)

        for row in [r for r in catalogue if r["dataset"] == dataset]:
            exp = (row["expected_nodes"], row["expected_relationships"])
            try:
                rc, out, err = shell(args, cypher=row["cypher"].strip() + ";\n")
                if rc != 0:
                    raise RuntimeError((err or out).strip().splitlines()[-1])
                actual = read_counts(out)
                note = ""
            except Exception as exc:  # noqa: BLE001 - reported, not swallowed
                actual = (None, None)
                note = str(exc)

            ok = actual == exp
            if not ok:
                failures += 1
            print("  %-6s %-58s expected %5s/%-5s  actual %5s/%-5s  %s%s" % (
                row["query_id"], row["description"][:58],
                exp[0], exp[1],
                "ERR" if actual[0] is None else actual[0],
                "ERR" if actual[1] is None else actual[1],
                "PASS" if ok else "FAIL",
                ("  <- " + note) if note else "",
            ), flush=True)
            results.append({
                "dataset": dataset,
                "query_id": row["query_id"],
                "expected_nodes": exp[0],
                "expected_relationships": exp[1],
                "actual_nodes": actual[0],
                "actual_relationships": actual[1],
                "result": "PASS" if ok else "FAIL",
                "note": note,
            })

        # The MERGE based dump claims to be idempotent - prove it by importing
        # it a second time and re-running the totals query.
        if dataset == "04-supply-chain":
            import_dump(args, dataset)
            totals = next(r for r in catalogue
                          if r["dataset"] == dataset and r["query_id"] == "c-01")
            exp = (totals["expected_nodes"], totals["expected_relationships"])
            rc, out, err = shell(args, cypher=totals["cypher"].strip() + ";\n")
            actual = read_counts(out) if rc == 0 else (None, None)
            ok = actual == exp
            if not ok:
                failures += 1
            print("  %-6s %-58s expected %5s/%-5s  actual %5s/%-5s  %s" % (
                "c-01r", "totals after a SECOND import (idempotency)",
                exp[0], exp[1], actual[0], actual[1], "PASS" if ok else "FAIL"),
                flush=True)
            results.append({
                "dataset": dataset,
                "query_id": "c-01-reimport",
                "expected_nodes": exp[0],
                "expected_relationships": exp[1],
                "actual_nodes": actual[0],
                "actual_relationships": actual[1],
                "result": "PASS" if ok else "FAIL",
                "note": "second import of the same dump",
            })

    with open(args.results, "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(results[0].keys()))
        writer.writeheader()
        writer.writerows(results)

    print("\n%d checks, %d passed, %d failed  (details in %s)" % (
        len(results), len(results) - failures, failures,
        os.path.relpath(args.results, HERE)), flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
