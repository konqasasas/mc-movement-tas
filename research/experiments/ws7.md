# 実験の登録簿: WS7 物理高速化

走らせる前に登録し、終わったら結果と F 番号を書き足します。条件をそろえて比べられるよう、コマンドはそのまま再実行できる形で書きます。

| 番号 | 日付 | 目的 | 条件・コマンド | 結果 | 知見 |
| --- | --- | --- | --- | --- | --- |
| E-301 | 2026-10-28 | 物理の高速化の前後比較 | main(`a4cc840`)とブランチ `claude/project-thread-k025c7` で `./gradlew :problem:bench -Prounds=6 -Pproblems=problems/v1-full/s2007-12j.problem.json,problems/place/01-pillar-2.problem.json,<test1.problem.json>,<stpt1.problem.json>`(test1 と stpt1 は共有フォルダ ws3b/) | 探索型の木 37〜45 万 → 88〜101 万ティック/秒、状態 1 つ約 240 バイトで変わらず、チェックサム一致 | F-301 |
