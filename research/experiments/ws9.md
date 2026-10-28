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
| E-509 | 2026-10-28 | 版 v2 の節目(human6、調整なし) | v2(7fcf917)で human6 の残り 4 問(remaining, poseidon, rampart, verdure)を 300 秒(threads 1、2 並列)、1 回だけ | remaining 328(人の記録 312、105%)。poseidon・rampart・verdure は解なし(v1 と同じく A* が止まる) | — |
| E-510 | 2026-10-28 | realdev(調整用)の版 v3 | `regress.sh`: realdev を 120 秒(threads 1、3 並列)、v1-dev・tower-dev を 60 秒(4 並列)、stpt1・test1 を 60 秒(2 並列)。`mctas-problem score` | realdev 6/6 の回あり(ex1 515、ex2 351、ex3 524、ex4 377、ex5 377、ex6 450)。ex5 は 120 秒ちょうどで見つかる境目で、4 回中 2 回解けた(380、377)。dev は v2 と同じ(v1-dev 8/8 で同じタイム、stpt1 201、test1 88、tower-dev 8/8) | F-510、F-511、F-512 |
| E-511 | 2026-10-28 | 要素別の自作 dev コース | `planner/courses/elem-dev` の 7 本(階段、ハーフ、頭ブロック、塀と柵の柱、板ガラスの列の蛇行、バリアのはしご、溝のはしごと柵)を 30 秒(4 並列)。v3(0def0a7)と角を足した版 | v3: 6/7(e05 蛇行が解なし)。角を足した版: 7/7(66, 95, 60, 88, 127, 101, 120) | F-514 |
| E-512 | 2026-10-28 | realdev(exp1〜3 を含む 9 問)の版 v4 | `regress.sh`(E-510 と同じ条件)と elem-dev 30 秒 | realdev 9/9: ex1 521、ex2 380、ex3 564、ex4 407、ex5 384、ex6 490、exp1 79、exp2 146、exp3 93(v3 は同じ条件の 1 回で 3/9 から 6/6 まで揺れ、exp2 は解なし)。v1-dev 8/8 で同じタイム、stpt1 212(v3 201〜207)、test1 88、tower-dev 8/8、elem-dev 7/7 | F-515、F-516、F-517 |
| E-513 | 2026-10-28 | 版 v4 の節目(human6、調整なし) | v4(47ab285)で human6 の残り 3 問(poseidon, rampart, verdure)を 300 秒(threads 1、2 並列)、1 回だけ | 3 問とも解なし(v3 と同じ) | — |
| E-514 | 2026-10-28 | 時間配分の変更(F-518)の回帰 | E-512 と同じ条件。2 割で打ち切る版(f)と、さらに重み 3 に 2/3 を渡す版(g) | f: ex1 505、ex2 381、ex3 552、ex4 394、ex5 352、ex6 466、exp1〜3 は 79・146・93、stpt1 219。g: ex1 510、ex2 381、ex3 552、ex4 391、ex5 374、ex6 480、stpt1 220。どちらも 9/9、v1-dev 8/8、tower-dev 8/8、elem-dev 7/7。f と g の差は回帰の揺れの中 | F-518 |
| E-515 | 2026-10-28 | gen-dev の最初の測定 | 版 g で gen-dev 15 問を 120 秒(3 並列) | 11/15(624、537、1009、—、969、—、—、906、—、1484、1169、1480、988、599、879)。g0004 は鉄格子の通路の先のジグザグのはしご、g0006・g0007・g0009 は高い所(117〜148)で止まる | F-519 |
