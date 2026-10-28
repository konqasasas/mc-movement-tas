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
| E-008 | 2026-10-28 | フルブロック設置の実機一致(PR #19 後) | 実機で `/mctas fuzz place 30`、エンジンビルド 1cfaeab93fb551d0 | 一致 19、不一致 1、INVALID 1、対象外 9 | F-012 |
| E-009 | 2026-10-28 | サーバー側の設置判定を入れた後の一致 | ブランチ `ws2/server-placement` で `./gradlew :oracle:fuzz -Pruns=10000 -Pcourses=place`、`-Pcourses=full` と `shapes` も | 不一致 0。2,000 本中 65 本が途中で `unsupportedAction` | F-012 |
| E-010 | 2026-10-28 | サーバー側の設置判定の実機一致 | 実機で `/mctas fuzz place 30`、エンジンビルド 6da35fe357332c3e | 一致 18、不一致 0、INVALID 3(1 本は予測どおりのサーバー拒否)、ラン間の位置戻し 1 | F-013 |
| E-011 | 2026-10-28 | 低い天井の扱いを入れた後の実機一致 | 実機で `/mctas fuzz place 30`、エンジンビルド 7022a7360e3cf9fd、原点 4 -36 | 一致 17、不一致 0、INVALID 4(3 本は予定どおりの `unsupportedAction`)。シード 29 の開始直後にサーバーの位置戻しが 3 回(原因は未特定。物理のずれではない) | F-013 |
| E-012 | 2026-10-28 | セーブの数値 ID の表の実機照合 | 実機で `/mctas checkids`(PR #24 の mod) | プロパティの違い 0。空気かどうかが 15 組違い、表を直した | F-014, F-015, F-018 |
| E-013 | 2026-10-28 | セーブ取り込みとゲーム内書き出しの一致、取り込んだ問題の実機再生 | 同じ場所を `mctas-problem import`(セーブ)と `/mctas mark`・`/mctas export`(ゲーム内)で書き出し、`mctas-problem diffworld` で比べる。その問題で `mctas-problem search` の解をマップのコピーで `/mctas replay` | 比較は一致(13 ブロック、ファイルも同じ)。再生は未実施 | F-015, F-019 |
| E-014 | 2026-10-28 | 近似探索 v1 の v0 成績 | ブランチ `claude/project-thread-e5m6fi`(`b73a506`)で `mctas-approx solve problems/v0 out --budget 15`、`mctas-problem score problems/v0 out` | 10 問すべて解け、9 問が目標どおり。08 は 24(最適 23)、10 は 33(目標 35) | F-016 |
| E-015 | 2026-10-28 | 近似探索 v1 の報告用成績 | 同じビルドで `mctas-approx solve problems/v1-holdout out --budget 30`、`v1-full` も 30 秒 | v1-holdout 12 問中 9 問、v1-full 8 問中 7 問を解いた | F-016 |
| E-016 | 2026-10-28 | v1 の目標タイム作り | 同じビルドで `--budget 120` を `v1-dev` と `v1-holdout` に | v1-holdout の 30 秒の解は 9 問中 6 問で 120 秒と同じ、残りは +1〜+4 | F-016 |
| E-019 | 2026-10-28 | 落下ダメージ無効の実機確認 | 実機で `/mctas replay ../../../../problems/fall/01-drop-6.problem.json ../../../../problems/fall/01-drop-6.tas` | 58 ティック、全ティック一致 | F-022 |
| E-022 | 2026-10-28 | 動画から stpt1 の人間の入力を復元 | `tools/video-tas/README.md` の手順(フレーム 4640〜4958、yaw 0)、`mctas-problem time stpt1.problem.json tools/video-tas/stpt1/stpt1-human187.tas --strict` | 全ティック一致、エンジンで 187 ティック。実機の `/mctas replay` でも問題なし | F-025 |
| E-025 | 2026-10-28 | stpt1 の段差区間の局所探索 | ブランチ `claude/project-thread-usu2am` で `mctas-technique segment <stpt1.problem.json> <stpt1.tas> 103 0 8.5 65 12 0 --width 20000 --max 80`(B は `156 0 8.5 78 12 0`、C は `192 0 goal 0 0 0`。人間の解は 122、159、183) | 近似 36/37/47 → 25/26/39。人間 24/28/38 → 24/27/38 | F-029 |
| E-026 | 2026-10-28 | 段差の踏み切りの窓 | 同じブランチで `mctas-technique window` | 高さ 1、走りで 0.79〜2.51。天井が 1 ブロック手前までなら後ろ向きのジャンプだけ | F-030 |
| E-401 | 2026-10-28 | 局所探索で近似の解の途中からゴールまで | ブランチ `claude/project-thread-usu2am` で `MCTAS_SPLICE=out.tas mctas-technique segment <stpt1.problem.json> <stpt1.tas> 7 0 goal 0 0 0 --width 20000 --max 240`(103 からも) | 7 から 191、103 から 196。違反なし | F-401 |
| E-402 | 2026-10-28 | stpt1 の 191 ティックの解の実機再生 | 実機で `/mctas replay stpt1.problem.json stpt1-191.tas`(問題は `/mnt/project-files/ws3b/stpt1/`) | 191 ティック、全ティック一致 | F-402 |
| E-403 | 2026-10-28 | stpt1 をスポーンから全自動で(助走の探索つき) | ブランチ `claude/project-thread-usu2am` で `mctas-technique runup <stpt1.problem.json> 3 out.tas 0 20000` | 187、違反なし | F-403 |
| E-404 | 2026-10-28 | 壁の梯子の登り | 同じブランチで `mctas-technique ladder 3000` | W 押しより 11〜13 ティック速い | F-404 |
