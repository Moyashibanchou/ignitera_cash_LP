package com.ignitera.backend.service;

import com.ignitera.backend.dto.ChatRequest;
import com.ignitera.backend.dto.GeminiRequest;
import com.ignitera.backend.dto.GeminiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * GeminiService
 *
 * Gemini 1.5 Flash API との通信を担当するサービスクラス。
 *
 * 主な責務:
 *   1. IGNITERAのAIコンサルタント人格をシステムプロンプトとして適用する
 *   2. フロントエンドから受け取った会話履歴を Gemini API のフォーマットへ変換する
 *   3. Gemini API を呼び出し、返答テキストを取り出して返す
 *   4. API エラー時の適切なハンドリングとログ記録
 *
 * 設定キー（application.yml）:
 *   google.ai.api-key  … .env の GEMINI_API_KEY から注入
 *   google.ai.api-url  … Gemini エンドポイント URL
 */
@Service
public class GeminiService {

    // =========================================================================
    // ロガー
    // =========================================================================

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    // =========================================================================
    // システムプロンプト — IGNITERAのAIコンサルタント人格定義
    // =========================================================================

    /**
     * Gemini API に毎リクエスト適用するシステムプロンプト。
     * IGNITERAのブランド価値観・コミュニケーション姿勢・ヒアリング戦略を定義する。
     */
    private static final String SYSTEM_PROMPT = """
            あなたは「IGNITERA（イグニテラ）」のAIコンサルタントです。
            IGNITERAは「技術をビジネス成果に翻訳するプロ集団」として、
            中小企業・スタートアップのDXを実践支援する技術チームです。

            ## あなたの役割
            難解な技術用語を使わず、クライアントが抱えるビジネス課題を一緒に整理し、
            現実的で具体的なデジタル解決策を提案してください。
            「技術の翻訳者」として、相手が本当に必要としているものを見極めてください。

            ## コミュニケーションの姿勢
            - 押しつけがましい営業トークは絶対に避け、まず相手の悩みを丁寧に引き出してください。
            - 短く親しみやすい言葉で答え、専門用語は必ず平易な言葉で補足してください。
            - 相手が話しやすい雰囲気を作り、一問一答形式で会話を深めてください。
            - 初回メッセージでは自己紹介を簡潔に行い、どんなお悩みでも気軽に話せることを伝えてください。

            ## 提案できるソリューション（例）
            - LINE Bot・チャットbot  : 顧客対応自動化、予約受付、FAQ対応
            - AI業務活用             : 文書作成支援、データ分析、画像認識、音声認識
            - Web・システム開発       : LP、ECサイト、社内ポータル、管理画面
            - 業務自動化             : API連携、RPA、データパイプライン
            - 業界特化ソリューション  : 飲食・小売・医療・不動産・製造・士業など

            ## ヒアリング戦略（段階的に自然に行う）
            会話が深まり、相手が課題解決に前向きになったタイミングで、
            以下の情報を会話の流れに沿って1つずつ丁寧にヒアリングしてください。

            1. 業種・業態（どのようなビジネスをされているか）
            2. 企業名・屋号（任意、差し支えなければ）
            3. 連絡先メールアドレス
               → 「より詳しい業界別の資料や初期構想案をお送りできます」と
                  必ずメールアドレスの用途を明示してからお聞きください。

            一度に複数を聞かず、会話のテンポを崩さないようにしてください。

            ## IGNITERAの強み（適切なタイミングで伝える）
            - 業界別に整理された課題解決の資料がある。
            - 初回相談から具体的な構想案・ワイヤーフレームを提示できる。
            - 小規模な実証実験（PoC）から始めることができる。

            ## 絶対に守るべきルール
            - 「売上2倍」「コスト半減」など、根拠のない数字・実績値は一切使用しないこと。
            - 効果の表現は「〜が期待できます」「〜の可能性があります」など、断定を避けること。
            - 競合他社の批判・名指し比較は行わないこと。
            - 個人情報を取得する際は、必ず利用目的を明示してから収集すること。
            """;

    // =========================================================================
    // 設定値（application.yml → .env 経由で注入）
    // =========================================================================

    /** Gemini API キー（.env の GEMINI_API_KEY から注入） */
    @Value("${google.ai.api-key}")
    private String apiKey;

    /** Gemini API エンドポイント URL */
    @Value("${google.ai.api-url}")
    private String apiUrl;

    // =========================================================================
    // 依存性注入（明示コンストラクタ）
    // =========================================================================

    /** WebClientConfig で定義された Bean（タイムアウト・ログフィルター設定済み）*/
    private final WebClient geminiWebClient;

    /**
     * Spring が WebClient Bean を注入するための明示コンストラクタ。
     * Lombok の @RequiredArgsConstructor を使わず手書きで定義する。
     *
     * @param geminiWebClient WebClientConfig で定義された Bean
     */
    public GeminiService(WebClient geminiWebClient) {
        this.geminiWebClient = geminiWebClient;
    }

    // =========================================================================
    // パブリックメソッド
    // =========================================================================

    /**
     * AIコンサルタントの返答テキストを生成する。
     *
     * <p>処理の流れ:
     * <ol>
     *   <li>システムプロンプト・会話履歴・今回のメッセージを Gemini リクエスト形式に変換</li>
     *   <li>Gemini 1.5 Flash API を POST で呼び出す</li>
     *   <li>レスポンスから返答テキストを取り出して返す</li>
     * </ol>
     *
     * @param request フロントエンドから送信された {@link ChatRequest}
     * @return Gemini API から返却されたAIの返答テキスト
     * @throws RuntimeException API 呼び出しに失敗した場合
     */
    public String generateReply(ChatRequest request) {
        log.info("[GeminiService] リクエスト受信 — メッセージ: {}文字, 履歴: {}件",
                request.message().length(),
                request.history() != null ? request.history().size() : 0);

        GeminiRequest geminiRequest = buildGeminiRequest(request);
        String requestUrl = apiUrl + "?key=" + apiKey;

        // APIキーをマスクしてログ出力（末尾4文字のみ表示）
        String maskedKey = apiKey != null && apiKey.length() > 4
                ? "***" + apiKey.substring(apiKey.length() - 4)
                : "***";
        log.info("[GeminiService] Gemini API へリクエスト送信 — URL={}, apiKey={}",
                apiUrl, maskedKey);

        try {
            GeminiResponse response = geminiWebClient
                    .post()
                    .uri(requestUrl)
                    .bodyValue(geminiRequest)
                    .retrieve()
                    // 4xx: 認証エラー・リクエスト不正など
                    .onStatus(HttpStatusCode::is4xxClientError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .flatMap(body -> {
                                        log.error("[GeminiService] 4xxエラー status={} body={}",
                                                clientResponse.statusCode(), body);
                                        return Mono.error(new RuntimeException(
                                                "Gemini API クライアントエラー ["
                                                + clientResponse.statusCode() + "]: " + body));
                                    })
                    )
                    // 5xx: Gemini サーバー側の問題
                    .onStatus(HttpStatusCode::is5xxServerError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .flatMap(body -> {
                                        log.error("[GeminiService] 5xxエラー status={} body={}",
                                                clientResponse.statusCode(), body);
                                        return Mono.error(new RuntimeException(
                                                "Gemini API サーバーエラー ["
                                                + clientResponse.statusCode() + "]: " + body));
                                    })
                    )
                    .bodyToMono(GeminiResponse.class)
                    .block(); // Spring MVC（同期）との互換性のため block() で待機

            if (response == null) {
                log.error("[GeminiService] レスポンスが null でした");
                throw new RuntimeException(
                        "AIからの応答が空でした。しばらく経ってから再度お試しください。");
            }

            String replyText = response.extractText();
            if (replyText == null || replyText.isBlank()) {
                // candidates の構造をダンプして原因調査を助ける
                int candidateCount = response.candidates() != null
                        ? response.candidates().size() : 0;
                log.warn("[GeminiService] 返答テキストが空 — candidates={}, rawError={}",
                        candidateCount, response.error());
                throw new RuntimeException(
                        "AIの応答テキストを取得できませんでした。(candidates=" + candidateCount + ")");
            }

            log.info("[GeminiService] レスポンス受信成功 — {}文字, candidates={}件",
                    replyText.length(),
                    response.candidates() != null ? response.candidates().size() : 0);
            return replyText;

        } catch (WebClientResponseException e) {
            log.error("[GeminiService] WebClientResponseException status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new RuntimeException(
                    "Gemini API との通信中にエラーが発生しました: " + e.getMessage(), e);

        } catch (RuntimeException e) {
            // onStatus から伝播したエラーはそのまま再スロー
            log.error("[GeminiService] 呼び出しエラー: {}", e.getMessage());
            throw e;

        } catch (Exception e) {
            log.error("[GeminiService] 予期せぬエラー", e);
            throw new RuntimeException(
                    "AIサービスへの接続中にエラーが発生しました。しばらく経ってから再度お試しください。", e);
        }
    }

    // =========================================================================
    // プライベートメソッド
    // =========================================================================

    /**
     * {@link ChatRequest} を Gemini API が受け付ける {@link GeminiRequest} に変換する。
     *
     * <p>組み立て順序:
     * <ol>
     *   <li>system_instruction にシステムプロンプトをセット</li>
     *   <li>過去の会話履歴を Content リストに変換（role を Gemini 仕様に正規化）</li>
     *   <li>今回のユーザーメッセージを末尾に追加</li>
     *   <li>generationConfig でトークン数・温度などのパラメータをセット</li>
     * </ol>
     *
     * <p>record 型に変更されたため、builder パターンの代わりに
     * {@code new GeminiRequest(...)} のコンストラクタ呼び出しを使用する。
     *
     * @param request フロントエンドからのリクエスト（record型）
     * @return Gemini API 送信用リクエストオブジェクト
     */
    private GeminiRequest buildGeminiRequest(ChatRequest request) {

        // 1. システムプロンプト
        // コンポーネント名 system_instruction により Jackson が "system_instruction" キーで送信する
        GeminiRequest.SystemInstruction system_instruction =
                new GeminiRequest.SystemInstruction(
                        List.of(new GeminiRequest.Part(SYSTEM_PROMPT))
                );

        // 2. 会話履歴の変換
        List<GeminiRequest.Content> contents = new ArrayList<>();

        if (request.history() != null && !request.history().isEmpty()) {
            for (ChatRequest.HistoryItem item : request.history()) {
                // record accessor: item.role(), item.content()
                contents.add(
                        new GeminiRequest.Content(
                                normalizeRole(item.role()),
                                List.of(new GeminiRequest.Part(item.content()))
                        )
                );
            }
        }

        // 3. 今回のユーザーメッセージ（末尾に追加）
        // record accessor: request.message()
        contents.add(
                new GeminiRequest.Content(
                        "user",
                        List.of(new GeminiRequest.Part(request.message()))
                )
        );

        // 4. 生成パラメータ
        GeminiRequest.GenerationConfig generationConfig =
                new GeminiRequest.GenerationConfig(
                        0.85,   // temperature  : 自然さと一貫性のバランス
                        2048,   // maxOutputTokens: 詳細な提案に対応できる十分な量
                        0.95,   // topP          : 高品質なトークン選択
                        40      // topK          : 多様性を適度に確保
                );

        return new GeminiRequest(system_instruction, contents, generationConfig);
    }

    /**
     * フロントエンドのロール名を Gemini API が受け付ける形式に正規化する。
     *
     * <ul>
     *   <li>{@code "assistant"} / {@code "ai"} → {@code "model"}</li>
     *   <li>{@code "user"}                     → {@code "user"}</li>
     *   <li>その他・null                        → {@code "user"}（フォールバック）</li>
     * </ul>
     *
     * @param role フロントエンドから送信されたロール文字列
     * @return Gemini API 仕様のロール文字列（{@code "user"} または {@code "model"}）
     */
    private String normalizeRole(String role) {
        if (role == null) return "user";
        return switch (role.toLowerCase().trim()) {
            case "assistant", "model", "ai" -> "model";
            default                         -> "user";
        };
    }
}
