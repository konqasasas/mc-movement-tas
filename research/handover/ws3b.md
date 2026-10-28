# 引き継ぎ: WS3b 近似探索(2026-10-28)

- **済み**: `approx` モジュール(経路グラフ、手書き方策による先読み、ティック単位のビーム、経路 3 本)。採点コマンド `mctas-problem score`、`ToggleRule`、解答の `result.search`。問題集 `v1-dev`(調整用)、`v1-holdout`(報告専用)、`v1-full`(石だけ、WS3 の最適値で品質を測る用)と生成器 `mctas-approx gen`。成績は F-016。
- **次**: `Refiner`(区間ごとの再探索と向きの微調整、今は何もしない)。v0/08 の +1。v1-full の最適値が WS3 から出たら差を測る。新しい最良解を実機の `/mctas replay` で確かめる。
- **測り方**: 調整は `v1-dev` だけで行い、報告は `v1-holdout` と `v1-full` の `score` の表で出す。採用した改良には approx/README.md の版表に版を振る。
