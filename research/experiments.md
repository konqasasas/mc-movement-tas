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
| E-017 | 2026-10-28 | 近似探索 v2(磨き込み)と v1 の比較 | ブランチ `claude/project-thread-e5m6fi`(`ce94d4a`)で v0 を 15 秒、`v1-dev` と `v1-holdout` を 30 秒 | v0 は 10 問すべて目標どおり(08 が 24 → 23)。v1-holdout は 9 問解けて合計 817(v1 は 816) | F-020 |
| E-018 | 2026-10-28 | 近似探索の解の実機再生 | 実機で `/mctas replay` に `problems/v1-holdout/s1008-5j`、`s1011-8j`、`problems/v1-dev/s0005-10j` の問題と `.tas` | 3 本とも予測どおり(60, 67, 134 ティック)で、全ティック一致 | F-021 |
| E-019 | 2026-10-28 | 落下ダメージ無効の実機確認 | 実機で `/mctas replay ../../../../problems/fall/01-drop-6.problem.json ../../../../problems/fall/01-drop-6.tas` | 58 ティック、全ティック一致 | F-022 |
| E-020 | 2026-10-28 | 実際のワールドから書き出した問題を解いて実機再生 | ユーザーが `/mctas export test1` で書き出した問題(10 ジャンプほど、石と羊毛)を `mctas-approx solve test1.problem.json out --budget 60`(approx v2)で解き、実機で `/mctas replay problems/test1.problem.json problems/test1.tas` | 86 ティック。実機も 86 ティックで全ティック一致 | F-023 |
| E-021 | 2026-10-28 | stpt1(低い天井・段差の通路)で人間の記録と比べる | `mctas-approx solve problems/real/stpt1.problem.json out --budget 60`、解を `mctas-problem time --trace` で区間に分ける | 232 ティック(人間は約 187)。遅れは天井の終わりと段差の 3 区間に集中 | F-024 |
| E-022 | 2026-10-28 | 動画から stpt1 の人間の入力を復元 | `tools/video-tas/README.md` の手順(フレーム 4640〜4958、yaw 0)、`mctas-problem time stpt1.problem.json tools/video-tas/stpt1/stpt1-human187.tas --strict` | 全ティック一致、エンジンで 187 ティック。実機の `/mctas replay` でも問題なし | F-025 |
| E-023 | 2026-10-28 | stpt1 を人間の解から磨く | `mctas-approx solve problems/real/stpt1.problem.json out --budget 120 --seed stpt1-human187.tas` | 187 のまま、改善なし(98 秒) | F-026 |
| E-024 | 2026-10-28 | F-024 の対応の悪化確認 | `mctas-approx gen /tmp/dev2 v1-dev2 9 16` を v2(`ce94d4a`)と `c41692a` で `--budget 20` | 14 問ずつ解け、2 問で良く 2 問で 1 ティック悪い | F-027 |
| E-200 | 2026-10-28 | F-024 の対応の悪化確認(続き) | `mctas-approx gen` でシード 25〜120 を足し、v2 と新版を `--budget 20`(48 問)と `--budget 5`(64 問)で 1 本ずつ。`v1-holdout` を `--budget 30` で 3 回 | 調整用は悪化なし、報告用は s1002 を落とす | F-200 |
| E-201 | 2026-10-28 | human6 の初回測定 | `mctas-approx solve problems/human6 out --budget 60 --quiet` を v2 と新版で、`mctas-problem score problems/human6 out-v2 out-v3` | どちらも 0/6 | F-201 |
| E-202 | 2026-10-28 | human6 の解けない理由の種別 | 経路 6 種を引き、方策だけを走らせて、経路の有無・止まった通過点・止まり方だけを出す使い捨てのクラス(`Route`、`Pilot.play`) | 2 問は経路なし、4 問は方策が序盤で落ちる | F-202 |
| E-203 | 2026-10-28 | 段差の跳び方を方策とビームに足す試み | `-Dbrakes`(ビームの手を足す)と `-Dsteps`(方策の規則)で `mctas-approx solve problems/real/stpt1.problem.json out --budget 60`、`PilotCheck -Dslack=1.0` で区間ごとのティック | 60 秒で改善なし、方策だけで 1 ティック速い | F-203 |
| E-204 | 2026-10-28 | realdev/ex1 で経路が切れる場所 | `ROUTE_DEBUG=1 mctas-approx solve problems/realdev/ex1.problem.json out --budget 1` で届く面と届かない面を出し、切れ目の前後のブロックを問題ファイルで見る | z 289 で切れ、その先はつたの区間 | F-204 |
| E-205 | 2026-10-28 | realdev の経路の切れ目 | `ROUTE_DEBUG=1 mctas-approx solve problems/realdev/exN.problem.json out --budget 1`、ex1 は切れ目の手前 (−44.5, 68, 287.5) から使い捨てのビーム(7 方向 × スプリント・ジャンプ・スニーク、幅 3000、3 ティックの規則あり) | 3 問とも切れる。ex1 はスニークとスプリントの交互で約 60 ティックで渡れる | F-205 |
| E-206 | 2026-10-28 | realdev 6 問の失敗の種別 | 使い捨ての `Diag`(経路 6 種と方策だけの結果)と `PilotCheck -Dslack=0/1.0 -Dlast=N`、問題ファイルの止まった場所の前後のブロック | ex1 は 21/27 まで、ex2・ex3 は経路なし、ex4 は指定の誤り、ex5 は 14/14 で停止、ex6 は助走で規則違反 | F-206 |
