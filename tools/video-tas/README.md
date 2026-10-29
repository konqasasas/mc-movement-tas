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

`stpt1/` に読み(`hud-readings.json`)、一致した入力(`matched-masks.txt`、最初の 4 ティックはテレポート直後の待ち)、助走をつけた解答(`stpt1-human187.tas`、`.solution.json`)がある。エンジンで 187 ティック、スプリント・スニーク切り替えの制約(D-004)の違反なし。詳しくは research/findings/shared.md の F-025。

## ティック表示のない動画(verdure の例)

verdure の動画(1280x720、30fps、座標は小数 5 桁、`F:` に向き、ティックカウンタなし)は、次の手順で復元した。動画そのものはリポジトリに入れていない。

1. 座標と向きを読む。`hud_ocr720.py`(フォントの 1 ピクセルは約 1.6、`X0`・`YS` は最初の数フレームの既知の読みに合わせた)。

   ```
   python3 tools/video-tas/hud_ocr720.py verdure.mp4 0 1126 readings.json
   ```

2. 位置が変わるたびに 1 ティックとして並べる。30fps では 1 ティックが 1.5 フレームなので、位置の変わる間隔は 1 か 2 フレームで交互になり、3 フレーム以上空く所がなければティックの抜けはない(verdure はテレポートからゴールまで 653 ティック、抜けなし)。各ティックには、前後のフレームに映った向きを候補として付ける(`verdure/hud-ticks.json`)。

3. 毎ティック、キーの組み合わせと向きを探す。

   ```
   java -Dturns=1 -cp "$CP:/tmp/vt" YawMatch PROBLEM.json hud-ticks.json 0 653 1.5e-5
   ```

   - 表示の向きは -180〜180 に巻いてある。ゲームの向きは正の側(`-Dturns=1`、表示 + 360)で、負のまま使うと正弦表の引き方(負の数の切り捨て)が 1 段ずれ、1 ティック 2.5e-5 ずつずれる。
   - ゲームは 30fps より速く描いているので、ティックが使う向きはどのフレームにも映らないことがある(653 ティックのうち 334)。そのときは、移動が `base + sin(yaw) u + cos(yaw) v` の形なのを使って向きを式で求め、正弦表の前後の段を確かめる。壁に当たるティックは向きを細かく走査する。
   - 位置は 5 桁に丸めてあるので、許す誤差は 1.5e-5。

4. スポーンから人間のテレポート位置に止まる助走(`Runup`)とつないで判定する(`Assemble`)。

## verdure の結果

`verdure/` に、ティックごとの読み(`hud-ticks.json`)、一致した入力(`matched-inputs.txt`)、助走をつけた解答(`verdure-human631.tas`、`.solution.json`)がある。エンジンで 631 ティック(記録は 632 ティック = 31.6 秒)、読みとのずれは最大 1.5e-5。スプリント・スニークの切り替えの間隔(D-004)の違反が 4 か所ある(人間は実機で 3 ティック未満の切り替えをしている)。詳しくは research/findings/ws14.md の F-1006。
