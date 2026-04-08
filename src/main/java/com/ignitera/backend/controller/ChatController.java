package com.ignitera.backend.controller;

import com.ignitera.backend.dto.ChatRequest;
import com.ignitera.backend.dto.ChatResponse;
import com.ignitera.backend.dto.LeadInfo;
import com.ignitera.backend.service.EmailService;
import com.ignitera.backend.service.GeminiService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * ChatController
 *
 * IGNITERAのAIコンサルチャットAPIのエントリーポイント。
 *
 * エンドポイント:
 *   POST /api/chat   — AIとの会話（メイン機能）
 *   GET  /api/health — サーバー稼働確認
 *
 * 処理フロー（POST /api/chat）:
 *   1. フロントエンド（Next.js）からメッセージ・会話履歴を受け取る
 *   2. GeminiService でAIの返答を生成する
 *   3. EmailService でユーザー入力・AI返答からリード情報を検知する
 *   4. リードを検知した場合、EmailService で Gmail 通知をバックグラウンド送信する
 *   5. AIの返答とリード検知フラグをフロントエンドへ返す
 *
 * CORS:
 *   グローバル設定は CorsConfig.java に定義済みだが、
 *   @CrossOrigin を明示することでエンドポイント単位の意図も文書化する。
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000", allowedHeaders = "*", methods = {RequestMethod.POST, RequestMethod.OPTIONS})
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final GeminiService geminiService;
    private final EmailService  emailService;

    /**
     * Spring が GeminiService・EmailService の Bean を注入するための明示コンストラクタ。
     * Lombok の @RequiredArgsConstructor を使わず手書きで定義する。
     */
    public ChatController(GeminiService geminiService, EmailService emailService) {
        this.geminiService = geminiService;
        this.emailService  = emailService;
    }

    // =========================================================================
    // メインエンドポイント
    // =========================================================================

    /**
     * AIコンサルチャットエンドポイント
     *
     * <p>リクエスト例:
     * <pre>
     * POST /api/chat
     * Content-Type: application/json
     *
     * {
     *   "message": "飲食店でLINE Botを導入したいのですが、何から始めればいいですか？",
     *   "history": [
     *     { "role": "user",  "content": "こんにちは" },
     *     { "role": "model", "content": "こんにちは！IGNITERAのAIコンサルタントです。" }
     *   ]
     * }
     * </pre>
     *
     * <p>レスポンス例（リード未検知）:
     * <pre>
     * HTTP 200 OK
     * {
     *   "message": "LINE Botの導入についてですね！まずは...",
     *   "leadDetected": false,
     *   "errorMessage": null
     * }
     * </pre>
     *
     * <p>レスポンス例（リード検知・メール通知送信済み）:
     * <pre>
     * HTTP 200 OK
     * {
     *   "message": "ありがとうございます！資料をお送りできます...",
     *   "leadDetected": true,
     *   "errorMessage": null
     * }
     * </pre>
     *
     * @param request バリデーション済みリクエストボディ（@NotBlank / @Size 検証済み）
     * @return AIの返答とリード検知結果を含む {@link ChatResponse}
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("[ChatController] POST /api/chat — message=\"{}\"({}文字) history={}件",
                truncateForLog(request.message(), 50),
                request.message().length(),
                request.history() != null ? request.history().size() : 0);

        try {
            // ------------------------------------------------------------------
            // Step 1: Gemini API でAIの返答を生成する
            // ------------------------------------------------------------------
            String aiReply = geminiService.generateReply(request);
            log.debug("[ChatController] AI返答生成完了 — {}文字", aiReply.length());

            // ------------------------------------------------------------------
            // Step 2: ユーザー入力 + AI返答からリード情報を検知する
            //
            // 検知対象:
            //   - メールアドレス（@ を含む文字列）
            //   - 企業名（株式会社・合同会社などの法人格キーワード）
            //   - 業種（飲食・IT・不動産・医療など17カテゴリのキーワード）
            //
            // 3項目のうち1つ以上が見つかった場合を「リードあり」と判定する。
            // ------------------------------------------------------------------
            String summary  = buildConversationSummary(request.message(), aiReply);
            LeadInfo leadInfo = emailService.detectLeadInfo(
                    request.message(),
                    aiReply,
                    summary
            );

            // ------------------------------------------------------------------
            // Step 3: リード検知時はバックグラウンドでメール通知を送信する
            //
            // @Async("mailTaskExecutor") により呼び出しは即時返り、
            // SMTP送信はメインスレッドをブロックしない。
            // 送信失敗もチャット応答には影響しない。
            // ------------------------------------------------------------------
            if (leadInfo.hasAnyLeadInfo()) {
                log.info("[ChatController] リード情報を検知 → メール通知をバックグラウンドで送信します");
                emailService.sendLeadEmail(leadInfo, request.plan());
            }

            // ------------------------------------------------------------------
            // Step 4: フロントエンドへレスポンスを返す
            // ------------------------------------------------------------------
            // record コンストラクタ: ChatResponse(message, leadDetected, errorMessage)
            ChatResponse response = new ChatResponse(aiReply, leadInfo.hasAnyLeadInfo(), null);

            log.info("[ChatController] レスポンス送信 — leadDetected={}", response.leadDetected());
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            // コントローラー内ロジックによるバリデーションエラー
            log.warn("[ChatController] バリデーションエラー: {}", e.getMessage());
            return ResponseEntity
                    .badRequest()
                    .body(new ChatResponse(null, false,
                            "リクエストの内容が正しくありません: " + e.getMessage()));

        } catch (RuntimeException e) {
            // Gemini API 通信エラー・タイムアウトなど
            log.error("[ChatController] AIサービスエラー: {}", e.getMessage(), e);
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ChatResponse(null, false,
                            "AIサービスとの通信中にエラーが発生しました。" +
                            "しばらく経ってから再度お試しください。"));

        } catch (Exception e) {
            // 予期せぬエラー（念のための最終安全網）
            log.error("[ChatController] 予期せぬエラーが発生しました", e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ChatResponse(null, false, "サーバー内部でエラーが発生しました。"));
        }
    }

    // =========================================================================
    // ヘルスチェックエンドポイント
    // =========================================================================

    /**
     * サーバー稼働確認エンドポイント
     *
     * <p>デプロイ後の疎通確認や監視ツールからの死活監視に使用する。
     *
     * <pre>
     * GET /api/health
     * → 200 OK  "IGNITERA Backend API is running 🔥"
     * </pre>
     *
     * @return 200 OK とステータスメッセージ
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        log.debug("[ChatController] GET /api/health");
        return ResponseEntity.ok("IGNITERA Backend API is running \uD83D\uDD25");
    }

    // =========================================================================
    // プライベートユーティリティメソッド
    // =========================================================================

    /**
     * ログ出力用にテキストを指定文字数で切り詰める。
     *
     * <p>ユーザーメッセージには個人情報が含まれる可能性があるため、
     * ログに残す際は必ずこのメソッドで短縮する。
     *
     * @param text      元テキスト
     * @param maxLength 最大文字数
     * @return 切り詰めたテキスト（元が短ければそのまま返す）
     */
    private String truncateForLog(String text, int maxLength) {
        if (text == null) return "(null)";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    /**
     * メール通知の「会話の要約」フィールド用文字列を構築する。
     *
     * <p>出力フォーマット:
     * <pre>
     * [ユーザー] メッセージの先頭 100 文字…
     * [AI] AI返答の先頭 200 文字…
     * </pre>
     *
     * @param userMessage ユーザーのメッセージ
     * @param aiReply     AIの返答
     * @return 整形された会話要約文字列
     */
    private String buildConversationSummary(String userMessage, String aiReply) {
        String user = (userMessage != null && userMessage.length() > 100)
                ? userMessage.substring(0, 100) + "…"
                : (userMessage != null ? userMessage : "");

        String ai = (aiReply != null && aiReply.length() > 200)
                ? aiReply.substring(0, 200) + "…"
                : (aiReply != null ? aiReply : "");

        return String.format("[ユーザー] %s%n[AI] %s", user, ai);
    }
}
