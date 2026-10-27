# mc-movement-tas

Minecraft Java Edition 1.12.2 の移動TASを自動で探すエンジンです。ワールド、スタート直方体、ゴール直方体を与えると、最速ティック数とその全ティックの入力列を返すことを目標にしています。

## モジュール

| モジュール | 中身 |
| --- | --- |
| `problem` | 問題ファイル形式、スタートとゴールの判定、出力形式、問題集 |
| `physics` | ヘッドレスの 1.12.2 プレイヤー物理 |

## 問題と判定

問題ファイルの形式、スタートとゴールの判定、入力列と解答の形式は [docs/problem-format.md](docs/problem-format.md) にあります。問題集は `problems/` にあります。

```
./gradlew :problem:installDist
problem/build/install/mctas-problem/bin/mctas-problem time problems/v0/04-gap-3.problem.json problems/v0/04-gap-3.tas
```

## ビルド

JDK 21 で `./gradlew build`(Windows は `gradlew.bat build`)。成果物は Java 8 向けにコンパイルされます。

## ライセンス

GPL-3.0。[ParkourCalculatorMod](https://github.com/Leg0shii/ParkourCalculatorMod)(GPL-3.0)を参照・流用します。
