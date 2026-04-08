package com.ignitera.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * AIチャットAPIへのリクエストDTO
 *
 * フロントエンド（Next.js）から POST /api/chat へ送信されるリクエストボディ。
 * record 型により、コンストラクタ・アクセサ・equals/hashCode/toString が自動生成される。
 *
 * 呼び出し例:
 *   request.message()  … ユーザーの入力テキスト
 *   request.history()  … 過去の会話履歴リスト
 */
public record ChatRequest(

        /**
         * ユーザーの入力メッセージ。
         * 空文字・null は許可しない。最大 2000 文字。
         */
        @NotBlank(message = "メッセージは必須です")
        @Size(max = 2000, message = "メッセージは2000文字以内で入力してください")
        String message,

        /**
         * 会話履歴。
         * フロントエンドで管理し、毎リクエスト時に全件送信する。
         * Gemini API のマルチターン会話に使用する。
         * 初回メッセージ時は null または空リストを渡す。
         */
        List<HistoryItem> history,

        String plan

) {

    /**
     * 会話履歴の1件分を表すネスト record。
     *
     * role    : 発言者ロール。"user"（ユーザー）または "model"（AI）を使用する。
     *           フロントエンドが "assistant" を送ってきた場合は
     *           GeminiService 側で "model" へ正規化される。
     * content : 発言内容テキスト。
     */
    public record HistoryItem(
            String role,
            String content
    ) {}
}
