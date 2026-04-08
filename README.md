# IGNITERA Backend API

IGNITERAのLP用バックエンドAPIです。  
Gemini APIを使用した「AIコンサルチャット」と、獲得したリードの「Discord通知」を提供します。

---

## 技術スタック

| カテゴリ | 技術 |
|---|---|
| フレームワーク | Spring Boot 3.2.x |
| 言語 | Java 17 |
| HTTP クライアント | WebClient (Spring WebFlux) |
| ユーティリティ | Lombok |
| AI | Gemini 1.5 Flash API |
| 通知 | Discord Webhook |

---

## プロジェクト構造

```
backend/
├── src/
│   ├── main/
│   │   ├── java/com/ignitera/backend/
│   │   │   ├── IgniteraBackendApplication.java   # メインクラス
│   │   │   ├── config/
│   │   │   │   ├── CorsConfig.java               # CORS設定
│   │   │   │   └── WebClientConfig.java          # WebClient Bean定義
│   │   │   ├── controller/
│   │   │   │   └── ChatController.java           # REST コントローラー
│   │   │   ├── dto/
│   │   │   │   ├── ChatRequest.java              # チャットリクエストDTO
│   │   │   │   ├── ChatResponse.java             # チャットレスポンスDTO
│   │   │   │   ├── GeminiRequest.java            # Gemini APIリクエストDTO
│   │   │   │   ├── GeminiResponse.java           # Gemini APIレスポンスDTO
│   │   │   │   └── LeadInfo.java                 # リード情報DTO
│   │   │   └── service/
│   │   │       ├── GeminiApiService.java         # Gemini API通信サービス
│   │   │       └── DiscordNotificationService.java # リード検知・Discord通知サービス
│   │   └── resources/
│   │       └── application.yml                   # アプリケーション設定
│   └── test/
│       └── java/com/ignitera/backend/
│           └── IgniteraBackendApplicationTests.java
└── pom.xml
```

---

## セットアップ手順

### 1. 前提条件

- Java 17 以上がインストールされていること
- Maven 3.8 以上がインストールされていること（または Maven Wrapper を使用）

### 2. リポジトリのクローン / ディレクトリ移動

```bash
cd backend
```

### 3. 環境変数の設定

`src/main/resources/application.yml` を開き、以下の値を実際のキーに置き換えてください。

```yaml
app:
  gemini:
    api-key: YOUR_GEMINI_API_KEY_HERE      # ← Gemini API キーに変更
    api-url: https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent
  discord:
    webhook-url: YOUR_DISCORD_WEBHOOK_URL_HERE  # ← Discord Webhook URLに変更
```

> ⚠️ **セキュリティ注意**  
> `application.yml` を Git にコミットしないでください。  
> 本番環境では環境変数または Secret Manager を使用することを強く推奨します。

#### 環境変数で設定する場合（推奨）

```bash
export APP_GEMINI_API_KEY="AIza..."
export APP_DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/..."
```

`application.yml` を以下のように書き換えることで環境変数から読み込めます：

```yaml
app:
  gemini:
    api-key: ${APP_GEMINI_API_KEY}
    api-url: https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent
  discord:
    webhook-url: ${APP_DISCORD_WEBHOOK_URL}
```

### 4. ビルド & 起動

```bash
# 依存関係のダウンロードとビルド
mvn clean package -DskipTests

# アプリケーションの起動
mvn spring-boot:run
```

または JAR を直接実行：

```bash
java -jar target/ignitera-backend-0.0.1-SNAPSHOT.jar
```

起動後、`http://localhost:8080` でアクセス可能になります。

---

## APIエンドポイント

### POST `/api/ai/chat` - AIコンサルチャット

IGNITERAのAIコンサルタントとの会話を行うメインエンドポイントです。

#### リクエスト

```
POST http://localhost:8080/api/ai/chat
Content-Type: application/json
```

```json
{
  "message": "LINE Botを導入したいのですが、費用はどのくらいかかりますか？",
  "history": [
    {
      "role": "user",
      "content": "こんにちは"
    },
    {
      "role": "model",
      "content": "こんにちは！IGNITERAのAIコンサルタントです。今日はどのようなお悩みをお持ちですか？"
    }
  ]
}
```

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| `message` | string | ✅ | ユーザーの入力メッセージ（最大2000文字） |
| `history` | array | ❌ | 過去の会話履歴。初回は空配列または省略可 |
| `history[].role` | string | ✅ | `"user"` または `"model"` (`"assistant"` も可) |
| `history[].content` | string | ✅ | 発言内容 |

#### レスポンス（成功時）

```json
HTTP 200 OK

{
  "message": "LINE Botの導入費用についてですが、機能の規模によって変わります...",
  "leadDetected": false,
  "errorMessage": null
}
```

#### レスポンス（リード検知時）

```json
HTTP 200 OK

{
  "message": "ありがとうございます！株式会社〇〇様の飲食業での活用事例をご紹介できます...",
  "leadDetected": true,
  "errorMessage": null
}
```

> `leadDetected: true` の場合、Discord Webhook へ自動通知が送信されています。

#### レスポンス（エラー時）

```json
HTTP 503 Service Unavailable

{
  "message": null,
  "leadDetected": false,
  "errorMessage": "AIサービスとの通信中にエラーが発生しました。しばらく経ってから再度お試しください。"
}
```

---

### GET `/api/ai/health` - ヘルスチェック

サーバーの稼働確認用エンドポイントです。

```
GET http://localhost:8080/api/ai/health
```

```
HTTP 200 OK

IGNITERA Backend API is running 🔥
```

---

## リード自動検知ロジック

以下のパターンをユーザー入力とAI返答の両方から検索します。

| 検知項目 | 検知方法 | 例 |
|---|---|---|
| **メールアドレス** | 正規表現（RFC 5322 簡易版） | `contact@example.com` |
| **企業名** | 法人格キーワード検索 | `株式会社〇〇`, `〇〇合同会社` |
| **業種** | 業種キーワードリスト照合 | `飲食`, `IT`, `不動産`, `医療` など |

3項目のうち **1つ以上** が検知された場合にリードとみなし、Discord へ通知します。

### Discord 通知フォーマット

```
🔥 新規リード検知！ - IGNITERA AI壁打ち

AIチャットで新しいリード情報が検出されました。速やかにフォローアップしてください。

📌 流入ルート   Route 1: AI壁打ち
🏢 企業名      株式会社〇〇
🏭 業種        飲食
📧 メールアドレス  contact@example.com
💬 会話の要約   [ユーザー] LINE Botを...
               [AI] ご興味をお持ちいただき...
🕐 検知日時    2024/01/15 14:32:11
```

---

## CORS 設定

デフォルトで `http://localhost:3000`（Next.js 開発サーバー）からのリクエストを許可しています。

本番環境のドメインを追加する場合は `application.yml` に以下を追記してください：

```yaml
app:
  cors:
    allowed-origins:
      - http://localhost:3000
      - https://your-production-domain.com
```

---

## Gemini API キーの取得方法

1. [Google AI Studio](https://aistudio.google.com/) にアクセス
2. 「Get API key」をクリック
3. 新しいプロジェクトを作成、または既存のプロジェクトを選択
4. 生成された API キーをコピーして `application.yml` に設定

---

## Discord Webhook URLの取得方法

1. 通知を受け取りたい Discord サーバーのチャンネルを開く
2. チャンネル設定 → 「連携サービス」→「ウェブフック」をクリック
3. 「新しいウェブフック」を作成
4. 「ウェブフックURLをコピー」をクリックして `application.yml` に設定

---

## フロントエンド（Next.js）との連携例

```typescript
// Next.js / TypeScript での呼び出し例
const sendMessage = async (message: string, history: HistoryItem[]) => {
  const response = await fetch("http://localhost:8080/api/ai/chat", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ message, history }),
  });

  if (!response.ok) {
    throw new Error("API request failed");
  }

  const data = await response.json();
  // data.message     : AIの返答テキスト
  // data.leadDetected: リード検知フラグ
  return data;
};
```

---

## ライセンス

© 2024 IGNITERA. All rights reserved.