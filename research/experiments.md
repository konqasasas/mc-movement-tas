# 実験の登録簿

走らせる前に登録し、終わったら結果と F 番号を書き足します。条件をそろえて比べられるよう、コマンドはそのまま再実行できる形で書きます。

| 番号 | 日付 | 目的 | 条件・コマンド | 結果 | 知見 |
| --- | --- | --- | --- | --- | --- |
| E-001 | 2026-10-27 | 物理と公式コードの一致 | `./gradlew :oracle:fuzz -Pruns=1000 -Pcourses=full\|shapes\|fluids` | 各 1,000 本で不一致 0 | F-001, F-006 |
| E-002 | 2026-10-28 | 部分ブロック・液体の実機一致 | 実機で `/mctas fuzz shapes 100` | 照合できた 66 本で不一致 0 | F-006, F-008 |
| E-003 | 2026-10-28 | 解答の実機再生 | 実機で `/mctas replay ../../../../problems/v0/02-gap-1.problem.json ../../../../problems/v0/02-gap-1.tas` | 4 ティック、全ティック一致 | F-007 |
| E-004 | 2026-10-28 | フルブロック設置の一致 | ブランチ `ws2/block-placement` で `./gradlew :oracle:fuzz -Pruns=10000 -Pcourses=place` | 不一致 0 | F-009 |
| E-005 | 2026-10-28 | 視線のレイキャストの一致 | ブランチ `ws2/block-placement` で `./gradlew :oracle:rayFuzz -Pruns=3000` | 不一致 0 | F-010 |
| E-006 | 2026-10-28 | フルブロック設置の実機一致 | 実機で `/mctas fuzz place 30` | 一致 1、不一致 1、INVALID 19(テストの作り方の問題) | F-011 |
| E-007 | 2026-10-28 | 設置つき解答の実機再生 | 実機で `/mctas replay ../../../../problems/place/01-pillar-2.problem.json ../../../../problems/place/01-pillar-2.tas` | 21 ティック、全ティック一致 | F-011 |
