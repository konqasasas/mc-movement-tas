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
| E-008 | 2026-10-28 | v0 の target 解が切り替え3ティック制約を守るか | `./gradlew :search:test --tests '*WitnessToggleTest'`(差し替え前の解で実行) | 09・10 が違反 | F-012 |
| E-009 | 2026-10-28 | v0 の最速タイムの証明 | `mctas-search bound --cell 0.25 problems/v0/*.problem.json`(02・06 は `--cell` なし)、上界は `mctas-search beam <問題> --width 4000` | 5 問で下界 = 上界 | F-013 |
| E-010 | 2026-10-28 | 細かい区画での下界 | `mctas-search bound --cell 0.1 problems/v0/{03-gap-2,05-up-1,07-side-2}.problem.json` | 03 = 9、05 = 5、07 = 7 | F-014 |
| E-011 | 2026-10-28 | x 方向だけ細かい区画での下界 | `mctas-search bound --cell 0.1 --max-steps 300000000 problems/v0/{07-side-2,09-three-jumps,10-three-jumps-zigzag}.problem.json`、05 は `--cell 0.05` | 09 = 33、07 = 7、10 = 31、05 = 5 | F-015 |
