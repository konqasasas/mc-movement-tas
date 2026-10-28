# 実験の登録簿: WS3 厳密探索

走らせる前に登録し、終わったら結果と F 番号を書き足します。条件をそろえて比べられるよう、コマンドはそのまま再実行できる形で書きます。

| 番号 | 日付 | 目的 | 条件・コマンド | 結果 | 知見 |
| --- | --- | --- | --- | --- | --- |
| E-101 | 2026-10-28 | v0 の target 解が切り替え3ティック制約を守るか | `./gradlew :search:test --tests '*WitnessToggleTest'`(差し替え前の解で実行) | 09・10 が違反 | F-101 |
| E-102 | 2026-10-28 | v0 の最速タイムの証明 | `mctas-search bound --cell 0.25 problems/v0/*.problem.json`(02・06 は `--cell` なし)、上界は `mctas-search beam <問題> --width 4000` | 5 問で下界 = 上界 | F-102 |
| E-103 | 2026-10-28 | 細かい区画での下界 | `mctas-search bound --cell 0.1 problems/v0/{03-gap-2,05-up-1,07-side-2}.problem.json` | 03 = 9、05 = 5、07 = 7 | F-103 |
| E-104 | 2026-10-28 | x 方向だけ細かい区画での下界 | `mctas-search bound --cell 0.1 --max-steps 300000000 problems/v0/{07-side-2,09-three-jumps,10-three-jumps-zigzag}.problem.json`、05 は `--cell 0.05` | 09 = 33、07 = 7、10 = 31、05 = 5 | F-104 |
| E-105 | 2026-10-28 | 05 の 5 ティックの可否と、加速度の見積もりの影響 | 総当たり(連続ジャンプの位相 × ストレイフ)、`bound --cell 0.1` を ROT = 1 で(健全でない実験) | 実測の最良 z = 1.69909(必要 1.7)、下界は 05 = 5、07 = 7、10 = 31 で変わらず | F-105, F-106 |
| E-106 | 2026-10-28 | どのコースでも出る速度の下界 | `mctas-search speed problems/v0/*.problem.json problems/v1-dev/*.problem.json problems/v1-full/*.problem.json <realdev ex1〜ex6(ex4 は書き出し直し版)> <human6 6 問> <stpt1>`(共有フォルダ benchmarks/、ws3b/stpt1/)、`./gradlew :search:test --tests '*SpeedBoundTest'` | realdev 131/141/13/130/155/236、stpt1 132、human6 は poseidon 210・prism 55・rampart 218・remaining 138・tohell 136・verdure 55。v0・v1-dev で既知の解を超えず、テスト通過 | F-107 |
| E-107 | 2026-10-28 | 足場をたどる下界 | `mctas-search speed <realdev ex1〜ex6> <stpt1> <human6 6 問>`(共有フォルダ benchmarks/、ws3b/stpt1/)、`./gradlew :search:test --tests '*HopBoundTest'` | realdev は変わらず(131/141/13/130/155/236)、prism 288・verdure 260・tohell 274。v0・v1-dev・fall・place の既知の解とその途中区間、ランダムなコース(full・shapes・fluids 各 15)の区間で、下界は実際のティック数を超えない | F-108 |

