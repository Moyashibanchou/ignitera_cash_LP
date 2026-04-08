package com.ignitera.backend.service;

import com.ignitera.backend.dto.LeadInfo;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EmailService
 *
 * 責務:
 *   1. ユーザー入力・AI返答からリード情報（メールアドレス・企業名・業種）を検知する
 *   2. リードを検知した場合、JavaMailSender（Gmail SMTP）で通知メールを送信する
 *
 * メール送信は @Async によりバックグラウンドスレッドで実行されるため、
 * チャット応答のメインスレッドをブロックしない。
 */
@Service
public class EmailService {

    // =========================================================================
    // ロガー
    // =========================================================================

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    // =========================================================================
    // 依存性注入（明示コンストラクタ）
    // =========================================================================

    /** application.yml の spring.mail.* 設定をもとに Spring Boot が自動構成する */
    private final JavaMailSender mailSender;

    /**
     * Spring が JavaMailSender Bean を注入するための明示コンストラクタ。
     * Lombok の @RequiredArgsConstructor を使わず手書きで定義する。
     *
     * @param mailSender Spring Boot が自動構成する JavaMailSender
     */
    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // =========================================================================
    // 設定値（application.yml → .env から注入）
    // =========================================================================

    @Value("${app.mail.from-address}")
    private String fromAddress;

    @Value("${app.mail.from-name}")
    private String fromName;

    @Value("${app.mail.to-address}")
    private String toAddress;

    // =========================================================================
    // リード検知 — 正規表現パターン定数
    // =========================================================================

    /**
     * メールアドレス検知パターン（RFC 5322 簡易版）
     * 例: user@example.com / info@company.co.jp
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 企業名検知パターン
     * 日本の法人格キーワードを含む表現を検知する。
     * 例: 株式会社〇〇 / 〇〇合同会社 / 一般社団法人〇〇
     */
    private static final Pattern COMPANY_PATTERN = Pattern.compile(
            "(?:株式会社|合同会社|有限会社|一般社団法人|特定非営利活動法人|NPO法人|医療法人|社会福祉法人)" +
            "[\\p{L}\\p{N}\\p{Punct}　\\s]{1,30}" +
            "|" +
            "[\\p{L}\\p{N}]{1,20}(?:株式会社|合同会社|有限会社)",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    /**
     * 業種検知パターン（17カテゴリ）
     * 代表的な業種キーワードと照合する。
     */
    private static final Pattern INDUSTRY_PATTERN = Pattern.compile(
            "(?:飲食|飲食店|レストラン|カフェ|居酒屋|ラーメン|寿司|焼肉)" +
            "|(?:小売|スーパー|コンビニ|ドラッグストア|アパレル|ファッション)" +
            "|(?:不動産|建設|建築|リフォーム|工務店|ハウスメーカー)" +
            "|(?:IT|システム開発|ソフトウェア|SaaS|テック|DX|デジタル)" +
            "|(?:医療|クリニック|病院|歯科|整骨院|薬局|介護|福祉)" +
            "|(?:教育|学習塾|塾|スクール|学校|予備校|家庭教師)" +
            "|(?:美容|サロン|ネイル|エステ|美容室|ヘアサロン|理容)" +
            "|(?:製造|工場|メーカー|製品|部品|精密機械)" +
            "|(?:物流|運送|配送|倉庫|輸送)" +
            "|(?:金融|保険|証券|銀行|ファイナンス|投資)" +
            "|(?:農業|農家|農産物|畜産|水産)" +
            "|(?:観光|ホテル|旅館|宿泊|旅行|トラベル)" +
            "|(?:EC|ネットショップ|オンラインショップ|通販)" +
            "|(?:広告|マーケティング|PR|メディア|出版|印刷)" +
            "|(?:人材|採用|HR|リクルート|派遣|転職)" +
            "|(?:コンサル|コンサルティング|経営支援)" +
            "|(?:士業|弁護士|税理士|社労士|行政書士|司法書士)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    // =========================================================================
    // 公開メソッド
    // =========================================================================

    /**
     * ユーザー入力・AI返答のテキストからリード情報を抽出する。
     *
     * @param userMessage         ユーザーの入力テキスト
     * @param aiResponse          AIの返答テキスト
     * @param conversationSummary 呼び出し元で整形した会話の要約文字列
     * @return 抽出結果を格納した {@link LeadInfo}（何も検知されなくても非nullで返す）
     */
    public LeadInfo detectLeadInfo(String userMessage,
                                   String aiResponse,
                                   String conversationSummary) {

        // ユーザー入力とAI返答を結合して一括検索
        String combined = joinText(userMessage, aiResponse);

        String email       = extractEmail(combined);
        String companyName = extractCompanyName(combined);
        String industry    = extractIndustry(combined);

        // record コンストラクタ: LeadInfo(companyName, industry, email, conversationSummary)
        LeadInfo leadInfo = new LeadInfo(companyName, industry, email, conversationSummary);

        if (leadInfo.hasAnyLeadInfo()) {
            log.info("[LeadDetection] リード情報を検知 — email={}, company={}, industry={}",
                    present(email)       ? "検知" : "未検知",
                    present(companyName) ? "検知" : "未検知",
                    present(industry)    ? "検知" : "未検知");
        } else {
            log.debug("[LeadDetection] リード情報は検知されませんでした");
        }

        return leadInfo;
    }

    /**
     * 検知したリード情報を Gmail 経由でメール通知する。
     *
     * <p>{@code @Async("mailTaskExecutor")} により
     * {@link com.ignitera.backend.config.AsyncConfig} のスレッドプールで非同期実行される。
     * 送信失敗はログに記録するのみでチャット応答には影響しない。
     *
     * @param leadInfo 送信するリード情報
     * @param plan     ユーザーが選択したプラン名（未選択の場合は null / 空文字）
     */
    public void sendLeadEmail(LeadInfo leadInfo) {
        sendLeadEmail(leadInfo, null);
    }

    @Async("mailTaskExecutor")
    public void sendLeadEmail(LeadInfo leadInfo, String plan) {
        if (!leadInfo.hasAnyLeadInfo()) {
            log.debug("[Email] 通知すべきリード情報がないためスキップします");
            return;
        }

        if (!present(toAddress)) {
            log.warn("[Email] app.mail.to-address が未設定のため通知をスキップします");
            return;
        }

        // 送信元アドレスが未設定の場合はスキップ
        if (!present(fromAddress)) {
            log.warn("[Email] app.mail.from-address が未設定のため通知をスキップします");
            return;
        }

        String subjectPlan = present(plan) ? plan.strip() : null;
        String subject = subjectPlan != null
                ? "【新規リード獲得】" + subjectPlan + "プランへの問い合わせ"
                : "【新規リード獲得】AI壁打ちルートより";

        try {
            log.info("[Email] リード通知メールの送信を開始します — to={}, company={}, industry={}, email={}",
                    toAddress,
                    present(leadInfo.companyName()) ? leadInfo.companyName() : "未検知",
                    present(leadInfo.industry())    ? leadInfo.industry()    : "未検知",
                    present(leadInfo.email())       ? leadInfo.email()       : "未検知");

            MimeMessage message = mailSender.createMimeMessage();
            // multipart=true で HTML 本文（インライン CSS）を有効にする
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(new InternetAddress(fromAddress, fromName, "UTF-8"));
            helper.setTo(toAddress);
            helper.setSubject(subject);
            helper.setText(buildHtmlBody(leadInfo, subjectPlan), /* html= */ true);

            mailSender.send(message);
            log.info("[Email] 送信成功 ✓ → to={}", toAddress);

        } catch (MessagingException e) {
            // SMTP 通信エラー・メッセージ構築エラー
            // チャット応答には影響させないためログのみ記録する
            log.error("[Email] SMTP 送信エラー (MessagingException): subject=【新規リード獲得】AI壁打ちルートより, cause={}",
                    e.getMessage(), e);
        } catch (UnsupportedEncodingException e) {
            // 送信者名のエンコードエラー
            log.error("[Email] エンコードエラー (UnsupportedEncodingException): fromName={}, cause={}",
                    fromName, e.getMessage(), e);
        } catch (Exception e) {
            // タイムアウト・認証失敗など予期せぬエラー
            log.error("[Email] 予期せぬエラー ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    public void sendDirectInquiryEmail(String customerEmail, String planName) {
        sendDirectInquiryEmail(null, null, customerEmail, null, planName);
    }

    public void sendDirectInquiryEmail(String companyName,
                                       String industry,
                                       String customerEmail,
                                       String phone,
                                       String planName) {
        String to = toAddress;

        if (!present(to)) {
            log.warn("[Email] 直接問い合わせ通知先が未設定のため通知をスキップします");
            return;
        }

        if (!present(fromAddress)) {
            log.warn("[Email] app.mail.from-address が未設定のため通知をスキップします");
            return;
        }

        String normalizedPlan = present(planName) ? planName.strip() : "";
        String normalizedCompany = present(companyName) ? companyName.strip() : "";

        String subject = "【至急・直接問い合わせ】" + normalizedPlan + "プランの資料請求（企業名：" + normalizedCompany + "）";

        String body = "プラン名：" + (present(planName) ? planName.strip() : "未選択") + "\n"
                + "企業名：" + (present(companyName) ? companyName.strip() : "未入力") + "\n"
                + "業種：" + (present(industry) ? industry.strip() : "未入力") + "\n"
                + "お客様のメアド：" + (present(customerEmail) ? customerEmail.strip() : "未入力") + "\n"
                + "電話番号：" + (present(phone) ? phone.strip() : "未入力") + "\n"
                + "\n"
                + "カードから直接詳細情報が入力されました。早急にアプローチを開始してください。";

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(new InternetAddress(fromAddress, fromName, "UTF-8"));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);

            mailSender.send(message);
            log.info("[Email] 直接問い合わせ通知メールを送信しました ✓ → to={}", to);

        } catch (MessagingException e) {
            log.error("[Email] SMTP 送信エラー (MessagingException): subject={}, cause={}",
                    subject, e.getMessage(), e);
        } catch (UnsupportedEncodingException e) {
            log.error("[Email] エンコードエラー (UnsupportedEncodingException): fromName={}, cause={}",
                    fromName, e.getMessage(), e);
        } catch (Exception e) {
            log.error("[Email] 予期せぬエラー ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    // =========================================================================
    // リード検知 — プライベートメソッド
    // =========================================================================

    /** userMessage と aiResponse を空白区切りで結合する（nullセーフ）*/
    private String joinText(String userMessage, String aiResponse) {
        StringBuilder sb = new StringBuilder();
        if (present(userMessage)) sb.append(userMessage).append(" ");
        if (present(aiResponse))  sb.append(aiResponse);
        return sb.toString();
    }

    /** テキストからメールアドレスを抽出する（最初にマッチしたものを返す）*/
    private String extractEmail(String text) {
        if (!present(text)) return null;
        Matcher m = EMAIL_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** テキストから企業名を抽出する（法人格キーワードでマッチ）*/
    private String extractCompanyName(String text) {
        if (!present(text)) return null;
        Matcher m = COMPANY_PATTERN.matcher(text);
        return m.find() ? m.group().strip() : null;
    }

    /** テキストから業種キーワードを抽出する */
    private String extractIndustry(String text) {
        if (!present(text)) return null;
        Matcher m = INDUSTRY_PATTERN.matcher(text);
        return m.find() ? m.group().strip() : null;
    }

    // =========================================================================
    // HTML メール本文ビルダー
    // =========================================================================

    /**
     * リード情報をもとに HTML 形式のメール本文を生成する。
     *
     * <p>レイアウト:
     * <ol>
     *   <li>グラデーションヘッダー（IGNITERAブランドカラー）</li>
     *   <li>アラートバナー（フォローアップ促進）</li>
     *   <li>リード情報フィールド（流入ルート・企業名・業種・メールアドレス）</li>
     *   <li>会話要約ボックス</li>
     *   <li>フッター（タイムスタンプ・システム名）</li>
     * </ol>
     *
     * <p>ユーザー入力由来の文字列はすべて {@link #escapeHtml(String)} を通じてXSSを防ぐ。
     *
     * @param leadInfo 検知されたリード情報
     * @return HTML 文字列
     */
    private String buildHtmlBody(LeadInfo leadInfo, String plan) {

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));

        // 各フィールドの表示値（未検知の場合はプレースホルダー）
        // record accessor: leadInfo.companyName(), leadInfo.industry(), etc.
        String company  = present(leadInfo.companyName())
                ? esc(leadInfo.companyName()) : null;
        String industry = present(leadInfo.industry())
                ? esc(leadInfo.industry())     : null;
        String email    = present(leadInfo.email())
                ? esc(leadInfo.email())         : null;
        String planName = present(plan)
                ? esc(plan) : null;
        String summary  = present(leadInfo.conversationSummary())
                ? esc(truncate(leadInfo.conversationSummary(), 600)) : null;

        return """
                <!DOCTYPE html>
                <html lang="ja">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1.0">
                  <title>新規リード獲得通知 | IGNITERA</title>
                  <style>
                    *{box-sizing:border-box;margin:0;padding:0}
                    body{
                      font-family:'Helvetica Neue',Arial,'Hiragino Kaku Gothic ProN',
                                   Meiryo,sans-serif;
                      background:#f0f2f5;
                      padding:24px 16px;
                      color:#1a1a2e;
                    }
                    .wrapper{max-width:620px;margin:0 auto}
                    .card{
                      background:#fff;
                      border-radius:16px;
                      overflow:hidden;
                      box-shadow:0 4px 24px rgba(0,0,0,.10);
                    }

                    /* ---- ヘッダー ---- */
                    .hd{
                      background:linear-gradient(135deg,#00C896 0%%,#0055FF 100%%);
                      padding:36px 32px 28px;
                      text-align:center;
                    }
                    .hd-logo{font-size:28px;font-weight:900;color:#fff;letter-spacing:4px}
                    .hd-sub{margin-top:6px;font-size:13px;color:rgba(255,255,255,.82);letter-spacing:1px}
                    .hd-badge{
                      display:inline-block;
                      margin-top:14px;
                      background:rgba(255,255,255,.22);
                      color:#fff;font-size:12px;font-weight:600;
                      padding:5px 16px;border-radius:20px;
                      border:1px solid rgba(255,255,255,.35);letter-spacing:1px;
                    }

                    /* ---- アラート ---- */
                    .alert{
                      margin:24px 28px 0;
                      background:#FFF8E1;
                      border-left:4px solid #FFC107;
                      border-radius:6px;
                      padding:12px 16px;
                      font-size:13px;color:#7B5800;line-height:1.6;
                    }

                    /* ---- ボディ ---- */
                    .body{padding:24px 28px 28px}
                    .sec-title{
                      font-size:11px;font-weight:700;color:#888;
                      text-transform:uppercase;letter-spacing:1.2px;
                      margin-bottom:14px;padding-bottom:6px;
                      border-bottom:1px solid #eee;
                    }

                    /* ---- フィールドグリッド ---- */
                    .grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:12px}
                    .full{grid-column:1/-1}
                    .field{
                      background:#f8f9fc;border:1px solid #e8eaf0;
                      border-radius:8px;padding:12px 14px;
                    }
                    .f-label{font-size:11px;color:#999;font-weight:600;letter-spacing:.5px;margin-bottom:5px}
                    .f-value{font-size:15px;font-weight:600;word-break:break-all}
                    .f-none{font-size:13px;color:#bbb;font-style:italic;font-weight:400}
                    .f-email a{color:#0055FF;text-decoration:none}
                    .pill{
                      display:inline-block;
                      background:#e8f5e9;color:#2e7d32;
                      font-size:13px;font-weight:700;
                      padding:4px 12px;border-radius:20px;
                    }

                    /* ---- 会話要約 ---- */
                    .summary-sec{margin-top:20px}
                    .summary-box{
                      background:#f0f7ff;border:1px solid #cce0ff;
                      border-radius:10px;padding:16px 18px;
                    }
                    .summary-box pre{
                      font-family:inherit;font-size:13px;
                      color:#333;line-height:1.75;
                      white-space:pre-wrap;word-break:break-word;
                    }

                    /* ---- フッター ---- */
                    .ft{
                      background:#f8f9fc;border-top:1px solid #eee;
                      padding:16px 28px;text-align:center;
                    }
                    .ft-ts{font-size:12px;color:#aaa;margin-bottom:4px}
                    .ft-sys{font-size:11px;color:#ccc;letter-spacing:1px}
                  </style>
                </head>
                <body>
                  <div class="wrapper">
                    <div class="card">

                      <div class="hd">
                        <div class="hd-logo">&#128293; IGNITERA</div>
                        <div class="hd-sub">新規リード獲得通知</div>
                        <div class="hd-badge">Route 1: AI壁打ち</div>
                      </div>

                      <div class="alert">
                        &#9889; AIチャットで新しいリード情報が検出されました。<br>
                        速やかにフォローアップしてください。
                      </div>

                      <div class="body">

                        <div class="sec-title">&#128203; リード情報</div>

                        <div class="grid">

                          <div class="field full">
                            <div class="f-label">&#128204; 流入ルート</div>
                            <div class="f-value"><span class="pill">Route 1: AI壁打ち</span></div>
                          </div>

                          <div class="field full">
                            <div class="f-label">&#127919; 興味のあるプラン</div>
                            %s
                          </div>

                          <div class="field">
                            <div class="f-label">&#127970; 企業名</div>
                            %s
                          </div>

                          <div class="field">
                            <div class="f-label">&#127981; 業種</div>
                            %s
                          </div>

                          <div class="field full f-email">
                            <div class="f-label">&#128231; メールアドレス</div>
                            %s
                          </div>

                        </div>

                        <div class="summary-sec">
                          <div class="sec-title">&#128172; 会話の要約</div>
                          <div class="summary-box">
                            <pre>%s</pre>
                          </div>
                        </div>

                      </div>

                      <div class="ft">
                        <div class="ft-ts">&#128336; 検知日時: %s</div>
                        <div class="ft-sys">IGNITERA Lead Detection System — このメールは自動送信されています</div>
                      </div>

                    </div>
                  </div>
                </body>
                </html>
                """.formatted(
                // プラン
                planName != null
                        ? "<div class=\"f-value\">" + planName + "</div>"
                        : "<div class=\"f-value f-none\">未選択</div>",
                // 企業名
                company  != null
                        ? "<div class=\"f-value\">" + company + "</div>"
                        : "<div class=\"f-value f-none\">未検知</div>",
                // 業種
                industry != null
                        ? "<div class=\"f-value\">" + industry + "</div>"
                        : "<div class=\"f-value f-none\">未検知</div>",
                // メールアドレス
                email    != null
                        ? "<div class=\"f-value\"><a href=\"mailto:" + email + "\">" + email + "</a></div>"
                        : "<div class=\"f-value f-none\">未検知</div>",
                // 会話要約
                summary  != null ? summary : "（要約なし）",
                // タイムスタンプ
                timestamp
        );
    }

    // =========================================================================
    // ユーティリティメソッド
    // =========================================================================

    /** null でも空文字でもない場合に true */
    private boolean present(String v) {
        return v != null && !v.isBlank();
    }

    /** 指定文字数を超えたら切り詰めて末尾に「…」を付ける */
    private String truncate(String text, int max) {
        return (text == null || text.length() <= max) ? text : text.substring(0, max) + "…";
    }

    /**
     * HTML 特殊文字をエスケープする。
     * ユーザー入力由来の文字列をメール本文に埋め込む際に必ず使用する。
     */
    private String esc(String text) {
        if (text == null) return "";
        return text
                .replace("&",  "&amp;")
                .replace("<",  "&lt;")
                .replace(">",  "&gt;")
                .replace("\"", "&quot;")
                .replace("'",  "&#x27;");
    }

    /** {@link #esc(String)} の短縮エイリアス（HTML ビルダー内での可読性向上） */
    private String escapeHtml(String text) {
        return esc(text);
    }
}
