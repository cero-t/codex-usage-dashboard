# codex-usage-dashboard

[English](README.md) | **日本語**

`codex-usage-dashboard` は、Codex の利用状況・クレジット/コスト推定・利用主体(trigger)をローカルで確認できる、単一バイナリのダッシュボードです。

Codex の OTLP ログを受信し、ローカルの Codex メタデータで補完し、現在の Codex 利用枠を取得して、ダッシュボードで可視化します。

Claude Code の OTLP `api_request` ログも、トークン数と USD コストの行として正規化できます。ダッシュボード上では Codex / Claude Code を別タブで表示し、Codex の USD コストは 1000 credits = $40 として概算、Claude Code のコストはトークン項目と Claude API の料金表から計算します。

## 必要なもの

- Codex CLI がインストール済み、サインイン済み
  - CLI がなくてもダッシュボード自体は動作し、OTLP から得たクレジットやイベントは表示されますが、利用率(%)のゲージは空のままになります
- Codex の OTLP エクスポート設定([Codex の OTLP 設定](#codex-の-otlp-設定))

## クイックスタート

ビルド済みバイナリは **macOS 専用**(Apple Silicon)です。[Releases](../../releases) ページからダウンロードして実行します:

```sh
unzip codex-usage-dashboard-macos-arm64.zip
chmod +x codex-usage-dashboard
./codex-usage-dashboard
```

現在のリリースバイナリは署名・notarize していません。macOS で "Apple could not
verify" と表示されて起動できない場合、ダウンロードしたファイルを信頼できるときだけ quarantine 属性を外して再実行してください:

```sh
xattr -d com.apple.quarantine codex-usage-dashboard
./codex-usage-dashboard
```

別のプラットフォームを使う場合や、自分でビルドしたい場合は、ソースからビルドしてください — [`dev_docs/development.md`](dev_docs/development.md) を参照。

ブラウザで開く:

```text
http://127.0.0.1:4318/
```

画面上部のタブで Codex / Claude Code を切り替えます。期間は相対期間、現在の
Codex 5h 利用枠、任意の from/to 範囲から選べます。

多くのチャートと利用状況テーブルは、パネルごとに **Cost / Tokens** を切り替え
られます。ある内訳は USD コストで見つつ、別の内訳はトークン数のまま確認できます。

既定では localhost のみで待ち受けます:

- OTLP gRPC: `127.0.0.1:4317`
- HTTP ダッシュボード / OTLP/HTTP protobuf: `127.0.0.1:4318`

ローカルデータベースは次の場所に作成されます:

```text
data/codex-usage-dashboard.sqlite
```

(バイナリを実行したディレクトリからの相対パス)

## Codex の OTLP 設定

基本構成では、Codex の OTLP ログ出力をこのダッシュボードの gRPC レシーバーへ直接送ります。`~/.codex/config.toml` を編集します:

```toml
[otel]
exporter = { otlp-grpc = { endpoint = "http://127.0.0.1:4317" } }
```

設定変更後は Codex を再起動してください。新しい Codex のアクティビティが、1分ほどでダッシュボードに反映され始めます。

新しい OTLP イベントを記録するには、Codex や Claude Code を使っている間このダッシュボードのプロセスを起動しておく必要があります。停止中に送信されたイベントは後から取り込まれません。

### すでに Codex の OTLP を他のオブザバビリティ基盤へ送っている場合

このダッシュボードは標準の OTLP ポート(gRPC `:4317`、HTTP `:4318`)で待ち受けるため、Codex から直接ここへ送れます。すでに [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) を同じポートで動かしている場合は、Codex は Collector に向けたままにして、そこからこのダッシュボードへコピーを分岐(fan-out)させ、このダッシュボードを標準ポートからずらしてください。

Quarkus は作業ディレクトリの `.env` ファイルを読み込むので、そこにポートの上書き設定を置きます:

```sh
# .env
QUARKUS_GRPC_SERVER_PORT=14317
QUARKUS_HTTP_PORT=14318
```

そのうえで、Collector から OTLP/HTTP protobuf を `http://127.0.0.1:14318/v1/logs`(または gRPC を `:14317`)へ送信させます。

## Claude Code の OTLP 設定

Claude Code 対応は任意です。Codex のローカル SQLite 補完ではなく、Claude Code の
OTLP log/events ストリームを使います。このダッシュボードを起動したうえで、Claude
Code の OTLP logs exporter をダッシュボードへ向けて実行します:

```sh
export CLAUDE_CODE_ENABLE_TELEMETRY=1
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4317
claude
```

ダッシュボードは、トークン項目を含む Claude Code の `api_request` log レコードを正規化します。USD コストは受信時にトークンタイプ別に計算します。ログに `cost_usd` が含まれる場合、その値は比較用に別途保持します。metrics や traces は受信できても、ダッシュボード用のチャートには保存しません。

Claude Code 側の詳細なテレメトリ設定は
[Claude Code Monitoring docs](https://code.claude.com/docs/en/monitoring-usage)
を参照してください。

## LAN からのアクセス

このダッシュボードは既定でローカル専用です。**認証はありません**。また生ログのドリルダウン(`/api/events/{id}/raw`)は、作業ディレクトリ・会話ID・ホスト名・アカウントメタデータなどを含みうる OTLP レコードをそのまま返します。LAN に公開すると、**ネットワーク上のあらゆる端末が認証なしでそれらのデータを読める**状態になります — 信頼できるネットワークでのみ行ってください。

意図的に LAN へ公開する場合:

```sh
QUARKUS_HTTP_HOST=0.0.0.0 \
QUARKUS_GRPC_SERVER_HOST=0.0.0.0 \
./codex-usage-dashboard
```

他の端末から `http://<machine-ip>:4318/` を開きます。

## 設定

Quarkus は作業ディレクトリの `.env` ファイルを読みます。
コピーして使える既定値は [`.env.example`](.env.example) にあります。
よく使う環境変数:

```sh
# ポート / バインドアドレス
QUARKUS_HTTP_HOST=127.0.0.1
QUARKUS_HTTP_PORT=4318
QUARKUS_GRPC_SERVER_HOST=127.0.0.1
QUARKUS_GRPC_SERVER_PORT=4317

# ローカルストレージ
QUARKUS_DATASOURCE_JDBC_URL='jdbc:sqlite:data/codex-usage-dashboard.sqlite?journal_mode=WAL&busy_timeout=10000'

# 補完に使う Codex のローカル状態
CODEX_STATE5_PATH="$HOME/.codex/state_5.sqlite"
CODEX_LOGS2_PATH="$HOME/.codex/logs_2.sqlite"
CODEX_BIN=codex

# ローカルテレメトリの保持期間
CODEX_USAGE_DASHBOARD_RETENTION_EVERY=1h
CODEX_USAGE_DASHBOARD_RETENTION_OTEL_LOG_RECORDS=14d
CODEX_USAGE_DASHBOARD_RETENTION_ANNOTATED_EVENTS=365d
CODEX_USAGE_DASHBOARD_RETENTION_USAGE_SAMPLES=365d
```

保持期間は、このアプリが持つテーブルごとに独立して適用されます。

- `otel_log_records`: 生の OTLP log レコードです。最も大きくなりやすく、生ログのドリルダウンにも使います。未処理の backlog を失わないよう、annotation cursor が通過した行だけ削除します。
- `annotated_events`: Codex / Claude Code のトークン数、コスト、trigger、エラーを正規化した行です。ダッシュボードのチャートや一覧に使います。
- `usage_samples`: Codex 利用枠の割合を定期取得したスナップショットです。

各保持期間は `0` または `disabled` にすると無期限保持になります。
生の OTLP 行を削除しても、すでに annotation 済みのチャート履歴は残ります。
ただし、その行の生ログドリルダウンと annotation replay はできなくなります。
SQLite は削除済み領域を再利用しますが、ファイルサイズがすぐ縮むとは限りません。
ディスク上のファイルを縮めたい場合は手動で `VACUUM` を実行してください。

## OTLP サポート

対応:

- `:4317` での OTLP/gRPC
- `:4318/v1/logs` での OTLP/HTTP protobuf
- gzip 圧縮された OTLP/HTTP protobuf ボディ
- トークン数と `cost_usd` を含む Claude Code OTLP log `api_request` レコード

非対応:

- OTLP/HTTP JSON(`Content-Type: application/json`)

OTLP/HTTP JSON のリクエストは `415 Unsupported Media Type` を返します。これは、設定を誤ったエクスポーターが「成功したように見えて実際にはデータを黙って捨てている」状態を防ぐためです。OTLP/HTTP エクスポーターは JSON ではなく protobuf(`encoding: proto`)で送るようにしてください。

## 開発ドキュメント

開発者向けのメモは [`dev_docs/`](dev_docs/) にあります。まずは次から:

- [`dev_docs/architecture.md`](dev_docs/architecture.md)
- [`dev_docs/development.md`](dev_docs/development.md)
- [`dev_docs/token-accounting.md`](dev_docs/token-accounting.md)

## ライセンス

[Apache License, Version 2.0](LICENSE) の下で提供しています。同梱する第三者
コンポーネント(Apache ECharts)の帰属表示は [`NOTICE`](NOTICE) を参照してください。
