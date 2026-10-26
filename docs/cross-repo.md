# Cross-repo with the CLI

Diff two snapshots from different repositories or checkouts with the standalone CLI. It is
for producer and consumer services that don't share a Gradle build but must agree on the
wire format.

## Cross-repo diff with the CLI { #cross-repo-diff-with-the-cli }

Extract a snapshot on each side with the Gradle plugin, then compare the two files with the
CLI. No single Gradle build has to see both sides. To get the CLI, see
[Installation → CLI](installation.md#cli).

```console
$ ./gradlew serialkompatExtract   # in repo/checkout A -> build/serialkompat/current.snapshot
$ cp build/serialkompat/current.snapshot /tmp/producer.snapshot

$ ./gradlew serialkompatExtract   # in repo/checkout B -> build/serialkompat/current.snapshot
$ cp build/serialkompat/current.snapshot /tmp/consumer.snapshot

$ serialkompat diff /tmp/producer.snapshot /tmp/consumer.snapshot
```

`serialkompat diff <baseline.snapshot> <current.snapshot>` treats the first file as the old
schema and the second as the new one. That matches the Gradle check, where `baselineRef` is
the old side and your current code is the new side.

Flags and exit codes (including `--direction`, `--format`, `--no-fail` and
`--allow-empty-baseline`) are in [Tasks & CLI](tasks-cli.md#cli). An empty baseline file
against a non-empty current one exits `2`; see
[Your first PR](quickstart.md#first-time-adoption).
