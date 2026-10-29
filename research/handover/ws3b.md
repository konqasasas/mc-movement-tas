# 引き継ぎ: WS3b 近似探索(2026-10-29 更新)

- **済み**: `approx` モジュール v1〜v6(版の表は approx/README.md)。
  - 経路グラフ、方策による先読み、ティック単位のビーム、磨き込み。
  - 方策の落ちた区間を区間探索で埋める `Bridge`(v5)。
  - 着地を単位にした最良優先探索 `Explore`(v6)。スライム、はしごの列、ジグザグのはしご、柱から柱への跳び移りの決め打ちの手。
  - 成績(v6、F-209): gen-dev 13/15、gen-holdout 15/15、`v1-holdout` 12/12、human5 2/5、waza3 0/5。realdev は 120 秒で ex1・exp1・exp3。
- **目標(ユーザーが決めたもの)**: 主目標は waza3 程度のコースを安定して解くこと。発展目標は waza5 まで。waza6〜11 は対象外。特殊な場面に合わせ込んで、普通のコースでの安定性を落とさない。
- **waza の扱い**: waza は別のサーバーのコースを再現したもので、公開リポジトリに入れない。問題ファイルは `/mnt/project-files/benchmarks/waza/` から読む。問題ファイル、ブロック配置、座標の詳しい記述、TAS はコミットや PR に含めない(結果の数字だけはよい)。
- **次にやること(優先順)**:
  1. waza の開発用で止まる場所を潰す。waza1h1(はしごつきの丸太の列と日照センサー)、waza1h4、waza2h3。止まる要素の種類だけを記録し、座標は書かない。
  2. gen-dev の残り(g0005、g0009)と、時間切れで解けたり解けなかったりする問題を安定させる。橋渡しの遅い段(遅い区間探索で 5〜10 秒、エクスプローラの幅広い探索)を短くする。
  3. realdev の ex2・ex5・exp2 を 120 秒で解く(長い予算なら解ける)。ex3・ex4・ex6 は未解決。
- **測り方**:
  - 調整: `v1-dev2`・`v1-dev3`(48 問、`mctas-approx gen` のシード 9〜56)、gen-dev、realdev、waza1(h1・h3・h4)、waza2(h3)、human6 の verdure。1 本ずつ走らせる(L-200)。
  - 採点から外すもの(仕掛けあり): waza1h2・waza1h5・waza2h1・waza2h2・waza2h4・waza2h5。
  - 報告用(版の節目ごとに固定設定で 1 回だけ): `v1-holdout`(30 秒)、human5(60 秒、poseidon・prism・rampart・remaining・tohell)、waza3(120 秒)、gen-holdout(120 秒)。
  - 解けた解は `/mnt/project-files/solutions/ws3b/` に置く。
- **調べ方**: 環境変数 `EXPLORE_DEBUG`(候補の一覧)、`EXPLORE_WHY`(候補が落ちた理由)、`EXPLORE_TIME`(区間探索の時間)、`EXPLORE_FROM=x,y,z`(そこに立った状態からエクスプローラだけ)。
