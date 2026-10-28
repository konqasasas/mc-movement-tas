# 実験の登録簿: WS8 局所テクニック

走らせる前に登録し、終わったら結果と F 番号を書き足します。条件をそろえて比べられるよう、コマンドはそのまま再実行できる形で書きます。

| 番号 | 日付 | 目的 | 条件・コマンド | 結果 | 知見 |
| --- | --- | --- | --- | --- | --- |
| E-401 | 2026-10-28 | 局所探索で近似の解の途中からゴールまで | ブランチ `claude/project-thread-usu2am` で `MCTAS_SPLICE=out.tas mctas-technique segment <stpt1.problem.json> <stpt1.tas> 7 0 goal 0 0 0 --width 20000 --max 240`(103 からも) | 7 から 191、103 から 196。違反なし | F-401 |
| E-402 | 2026-10-28 | stpt1 の 191 ティックの解の実機再生 | 実機で `/mctas replay stpt1.problem.json stpt1-191.tas`(問題は `/mnt/project-files/ws3b/stpt1/`) | 191 ティック、全ティック一致 | F-402 |
| E-403 | 2026-10-28 | stpt1 をスポーンから全自動で(助走の探索つき) | ブランチ `claude/project-thread-usu2am` で `mctas-technique runup <stpt1.problem.json> 3 out.tas 0 20000` | 187、違反なし | F-403 |
| E-404 | 2026-10-28 | 壁の梯子の登り | 同じブランチで `mctas-technique ladder 3000` | W 押しより 11〜13 ティック速い | F-404 |
| E-405 | 2026-10-28 | ネオの区間探索 | ブランチ `claude/project-thread-usu2am` で `mctas-technique neo 5000` | 壁の 1/2/3 先で 17/18/20、4 先は見つからない | F-405 |
| E-406 | 2026-10-28 | 天井の下の連続ジャンプ | ブランチ `claude/project-thread-usu2am` で `mctas-technique ceiling 5000` | 20 ブロックで走り 70、天井 2 で 43、2.5 で 51、3 で 57、なし 58 | F-406 |
| E-407 | 2026-10-28 | 90 度の角 | ブランチ `claude/project-thread-usu2am` で `mctas-technique corner 5000` | 幅 1/2/3 で 73/66/63 | F-407 |
| E-408 | 2026-10-28 | stpt1 の 187 ティックの解の実機再生 | 実機で `/mctas replay stpt1.problem.json stpt1-187.tas` | 問題なし(ユーザーの報告) | F-408 |
| E-409 | 2026-10-28 | realdev の詰まる区間の局所探索 | ブランチ `claude/project-thread-usu2am` で `mctas-technique region ...`(technique/README.md の表) | つた 75、塀の柱 60(chain)、1 ブロックのはしご 33、水柱 57 | F-409 |
| E-410 | 2026-10-28 | 鉄のトラップドアの表 | ブランチ `claude/project-thread-usu2am` で `mctas-technique ceiling 3000`、`window`、`trapdoor 3000` | 天井 1.8125 で 38、段差 1.1875 の窓 1.37〜1.93、開いたもの 38/39/38 | F-410 |
