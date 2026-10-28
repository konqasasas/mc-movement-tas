# 動画から人間の入力列を復元する

座標表示(小数 8 桁)とティックカウンタが画面に出ている走りの動画から、ティックごとの入力を復元します。キー表示は照合に使うだけで、入力そのものは座標から決めます。

## 手順(stpt1 の例)

動画はブランチ `data/stpt1-video` の `stpt1_run.mp4`(1080p30、YouTube `k7RIi5rUc6M`)。Sasterisk24 さんの 187 ティックのテイクはフレーム 4640〜4958。

1. 座標とティックを読む。

   ```
   python3 tools/video-tas/hud_ocr.py stpt1_run.mp4 4640 4958 hud-readings.json
   ```

   1 ティックは 1.5 フレームなので、全ティックが少なくとも 1 フレームに映る。同じティックを映した 2 フレームの読みが食い違えば「conflict」と出る(stpt1 では 0)。HUD の位置は動画ごとに違うので、`hud_ocr.py` 冒頭の `S, X0, YS, TX0, TY` を合わせる。ffmpeg は 4:2:0 の動画を偶数の座標でしか切り抜かないことに注意。

2. 読みに一致する入力を探す(`problem` を `installDist` しておく)。

   ```
   CP='problem/build/install/mctas-problem/lib/*'
   javac -d /tmp/vt -cp "$CP" tools/video-tas/java/*.java
   java -cp "$CP:/tmp/vt" Match PROBLEM.json hud-readings.json 14820 15030 2e-8 0
   ```

   最初のティックの位置に立った状態から、毎ティック全部のキーの組み合わせを試し、次の読みと 2e-8 以内で一致する状態だけを残す。yaw は画面の `F:` の表示(stpt1 は 0)。

3. 問題のスポーンから、人間の開始位置に止まる助走を探す。

   ```
   java -cp "$CP:/tmp/vt" Runup PROBLEM.json 8.9375 22.12414
   ```

   S キー k ティックと、別の yaw で 1 ティックだけ押して、動きが 0 になるまで待つ。1.12.2 は 0.003 未満の速度を 0 にするので、止まる位置は正確に決まる。

4. つないで判定し、`.tas` を書く。

   ```
   java -cp "$CP:/tmp/vt" Build PROBLEM.json "$(cat runup.txt)" "$(cat masks.txt)" hud-readings.json 14825 out.tas
   problem/build/install/mctas-problem/bin/mctas-problem time PROBLEM.json out.tas --strict --solution out.solution.json
   ```

## stpt1 の結果

`stpt1/` に読み(`hud-readings.json`)、一致した入力(`matched-masks.txt`、最初の 4 ティックはテレポート直後の待ち)、助走をつけた解答(`stpt1-human187.tas`、`.solution.json`)がある。エンジンで 187 ティック、スプリント・スニーク切り替えの制約(D-004)の違反なし。詳しくは research/findings.md の F-025。
