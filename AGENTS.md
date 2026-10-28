# エージェント向けの案内

作業を始める前に [research/lessons/](research/lessons/) の全ファイルと [research/decisions.md](research/decisions.md) を読んでください。測って分かったことは `research/findings/<ワークストリーム>.md` に、走らせる実験は `research/experiments/<ワークストリーム>.md` に書きます(自分のワークストリームのファイルだけに書く)。書き方は [research/README.md](research/README.md) にあります。

`physics` か `problem` のソースを変えたら、`./gradlew :problem:writeEngineBuild` でエンジンのビルド ID を更新してコミットしてください(忘れると `EngineBuildTest` が落ちます)。
