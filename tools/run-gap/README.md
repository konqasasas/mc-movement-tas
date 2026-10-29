# 人間との差を区間ごとに見る

同じ問題の 2 本の走り(人間の入力列と探索の解など)を比べ、どこで遅れているかを出します。`problem` と `technique` を `installDist` しておきます。

```
CP='technique/build/install/mctas-technique/lib/*'
javac -d /tmp/rg -cp "$CP" tools/run-gap/java/*.java
java -cp "$CP:/tmp/rg" RunGap PROBLEM.json REF.tas OTHER.tas [--min 12] [--csv out.csv] [--json out.json]
java -cp "$CP:/tmp/rg" Chain PROBLEM.json RUN.tas OUT.tas [--min 20] [--width 20000] [--keep 2] [--radius 1.5] [--max 80]
```

- `RunGap`: 2 本をスタートからゴールまで再生し、足の位置の動的時間伸縮で OTHER の各ティックに REF のティックを対応づけます。OTHER をその着地(`--min` ティック以上離す)で区間に切り、区間ごとに、かかったティック数の差(loss)、累計の遅れ(lag)、2 本の位置の最大のずれ(dev、大きいと道筋が違う)、水平の速さ、W を押しているのにスプリントしていないティック数、壁に当たったティック数、多いキーを出します。`--csv` は 2 本の毎ティックの状態(位置、速さ、接地、スプリント、スニーク、壁、キー、視点、対応づけ)です。
- `Chain`: 走りをその着地で区間に切り、各区間を WS8 の `SegmentSearch` で「元の着地点から `--radius` 以内の同じ高さの面への着地」まで探し直し(最後はゴールの箱)、上位 `--keep` 本を次の区間に引き継ぎます。道筋を変えずに、動き方だけでどれだけ縮むかを見ます。区間ごとの累計の差を出し、最後に判定して OUT に書きます。

stpt1 と verdure の結果は research/findings/ws14.md(F-1001〜F-1005)。
