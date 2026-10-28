# 実験の登録簿: WS3b 近似探索

走らせる前に登録し、終わったら結果と F 番号を書き足します。条件をそろえて比べられるよう、コマンドはそのまま再実行できる形で書きます。

| 番号 | 日付 | 目的 | 条件・コマンド | 結果 | 知見 |
| --- | --- | --- | --- | --- | --- |
| E-200 | 2026-10-28 | F-024 の対応の悪化確認(続き) | `mctas-approx gen` でシード 25〜120 を足し、v2 と新版を `--budget 20`(48 問)と `--budget 5`(64 問)で 1 本ずつ。`v1-holdout` を `--budget 30` で 3 回 | 調整用は悪化なし、報告用は s1002 を落とす | F-200 |
| E-201 | 2026-10-28 | human6 の初回測定 | `mctas-approx solve problems/human6 out --budget 60 --quiet` を v2 と新版で、`mctas-problem score problems/human6 out-v2 out-v3` | どちらも 0/6 | F-201 |
| E-202 | 2026-10-28 | human6 の解けない理由の種別 | 経路 6 種を引き、方策だけを走らせて、経路の有無・止まった通過点・止まり方だけを出す使い捨てのクラス(`Route`、`Pilot.play`) | 2 問は経路なし、4 問は方策が序盤で落ちる | F-202 |
| E-203 | 2026-10-28 | 段差の跳び方を方策とビームに足す試み | `-Dbrakes`(ビームの手を足す)と `-Dsteps`(方策の規則)で `mctas-approx solve problems/real/stpt1.problem.json out --budget 60`、`PilotCheck -Dslack=1.0` で区間ごとのティック | 60 秒で改善なし、方策だけで 1 ティック速い | F-203 |
| E-204 | 2026-10-28 | realdev/ex1 で経路が切れる場所 | `ROUTE_DEBUG=1 mctas-approx solve problems/realdev/ex1.problem.json out --budget 1` で届く面と届かない面を出し、切れ目の前後のブロックを問題ファイルで見る | z 289 で切れ、その先はつたの区間 | F-204 |
| E-205 | 2026-10-28 | realdev の経路の切れ目 | `ROUTE_DEBUG=1 mctas-approx solve problems/realdev/exN.problem.json out --budget 1`、ex1 は切れ目の手前 (−44.5, 68, 287.5) から使い捨てのビーム(7 方向 × スプリント・ジャンプ・スニーク、幅 3000、3 ティックの規則あり) | 3 問とも切れる。ex1 はスニークとスプリントの交互で約 60 ティックで渡れる | F-205 |
| E-206 | 2026-10-28 | realdev 6 問の失敗の種別 | 使い捨ての `Diag`(経路 6 種と方策だけの結果)と `PilotCheck -Dslack=0/1.0 -Dlast=N`、問題ファイルの止まった場所の前後のブロック | ex1 は 21/27 まで、ex2・ex3 は経路なし、ex4 は指定の誤り、ex5 は 14/14 で停止、ex6 は助走で規則違反 | F-206 |
| E-207 | 2026-10-28 | v4 の採用前の確認 | `mctas-approx solve` を `v1-dev2`+`v1-dev3`(48 問、`--budget 20`)、`problems/v0`(15)、`problems/real`(60)、`problems/v1-holdout`(30)、`problems/human6`(60)で 1 本ずつ、v3 と比べる | 調整用は 1 問 +1 のみ、v0・real は同じ、報告用 8/12 で同じ、human6 0/6 | F-207 |
