# 実験の登録簿: WS9 階層型プランナー

走らせる前に登録し、終わったら結果と F 番号を書き足します。条件をそろえて比べられるよう、コマンドはそのまま再実行できる形で書きます。

| 番号 | 日付 | 目的 | 条件・コマンド | 結果 | 知見 |
| --- | --- | --- | --- | --- | --- |
| E-500 | 2026-10-28 | 開発用セットでの成績 | `mctas-planner solve <dir> out --budget 60`(threads 1、4 並列)を v1-dev、problems/real(stpt1, test1)、自作 tower-dev(`tools/towergen/towergen.py out tower-dev 1 8`)で。`mctas-problem score` | v1-dev 8/8(6 問が上限以下)、stpt1 204、test1 88、tower-dev 7/8 | F-500 |
| E-501 | 2026-10-28 | はしごで止まる原因 | `PLANNER_DEBUG=1 mctas-planner solve towerx-dev/t0004 --budget 90`(`towergen.py out towerx-dev 1 4 --segments 40 --decor 600 --floor`)、見積もりに登りの時間を足す前と後 | 止まる位置が見積もり残り 54.7 → 36.0 | F-501 |
| E-502 | 2026-10-28 | つたの区間 | realdev/ex1 を 120〜300 秒、まとまりの辺と `ClimbStep` の有無で | 解なし → 488〜522 | F-502 |
| E-503 | 2026-10-28 | 壁越しの辺 | realdev/ex3 の見積もりと止まる位置、入出の壁判定の有無で | 見積もり 89 → 135 | F-503 |
| E-504 | 2026-10-28 | 柵で分かれる台 | realdev/ex5 の見積もりと止まる位置、足場の分割の有無で | 見積もり 164 → 186、止まる位置 113 → 35 | F-504 |
| E-505 | 2026-10-28 | 報告用セット(調整なし) | v1-holdout を 60 秒(threads 1、4 並列)、human6 を 300 秒(threads 2、2 並列)。この PR の最終版で 1 回だけ | holdout 12/12(7 問が上限以下)、human6 prism 595・tohell 734、ほか解なし | F-500 |
| E-506 | 2026-10-28 | realdev(調整用)の版 v2 | `runset.sh realdev out 120 3`(threads 1、3 並列)、`mctas-problem score` | ex1 515、ex2 387、ex5 383、ex6 453、ex3・ex4v2 解なし(4/6)。途中の最良は ex2 379、ex5 371 | F-505、F-508 |
| E-507 | 2026-10-28 | ex3 の切れ目 | `Dbg9`(台ごとの見積もり)で、水のつながり・平らなゴール・スライムの辺の有無を比べる | 切れ目は z 257(水の階段)と z 264(スライム)。前者は解消、後者は残る | F-506、F-507、F-509 |
| E-508 | 2026-10-28 | v2 の dev 回帰 | E-500 と同じ条件 | v1-dev 8/8(52, 72, 89, 111, 131, 102, 144, 52 で v1 と同じ)、stpt1 201(v1 204)、test1 88、tower-dev 8/8(v1 7/8) | — |
