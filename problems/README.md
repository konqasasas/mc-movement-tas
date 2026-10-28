# 問題集

形式は [../docs/problem-format.md](../docs/problem-format.md) にあります。

## v0(平地とフルブロック、1〜3ジャンプ)

全問、石の1ブロック幅の通路 `x = 0, y = 64, z = -30..0` の端にスポーンし(`(0.5, 65, -29.5)`、南向き)、スタート直方体は通路の先端のブロック `z = 0..1` です。助走は30ブロック分あります。ゴールは着地先ブロックの上面です。

| 問題 | 内容 | 目標(ティック) | 種類 | 証明された下界 |
| --- | --- | --- | --- | --- |
| 01-run-10 | 平地を10ブロック先まで | 26 | optimal | 26 |
| 02-gap-1 | 隙間1(2bジャンプ) | 4 | optimal | 4 |
| 03-gap-2 | 隙間2(3bジャンプ) | 9 | upper_bound | 8 |
| 04-gap-3 | 隙間3(4bジャンプ) | 11 | optimal | 11 |
| 05-up-1 | 1段上へ、隙間1 | 6 | upper_bound | 5 |
| 06-down-1-gap-4 | 1段下へ、隙間4 | 14 | optimal | 14 |
| 07-side-2 | 斜め前(x+2, z+3)へ | 9 | upper_bound | 7 |
| 08-two-jumps | 隙間3、1ブロック足場、隙間2 | 23 | optimal | 23 |
| 09-three-jumps | 1ブロック足場で隙間2を3回 | 33 | upper_bound | 32 |
| 10-three-jumps-zigzag | 足場を左右に振った3連続 | 33 | upper_bound | 31 |

入力列 `NN-*.tas` が目標のティック数でゴールすることを `ProblemSetTest` で、スプリントとスニークの切り替えが3ティック以上空いていることを `search` の `WitnessToggleTest` で確かめています。`optimal` は、`mctas-search bound` の下界が目標と一致し、それより速い入力列が無いと証明できた問題です(`search/README.md`)。`upper_bound` の問題は、下界と目標の差が残っています。

09 と 10 の以前の解(33 と 35 ティック)は、切り替えの間隔が3ティック未満の箇所があったため、制約を守る解に差し替えました(research/findings.md F-012)。

## place: ブロック設置

`version: 2` の問題で、`player.inventory` にフルブロックを持ってスタートします。設置の仕様は [../docs/problem-format.md](../docs/problem-format.md) の「プレイヤーの持ち物(v2)」にあります。

| 問題 | 内容 | 目標ティック |
| --- | --- | --- |
| 01-pillar-2 | 真下にブロックを置いて2段上がる(実機の確認用) | 21 |
