package com.ignitera.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Gemini API レスポンスのマッピング用 DTO
 *
 * 実際のレスポンス例:
 * {
 *   "candidates": [
 *     {
 *       "content": {
 *         "parts": [{ "text": "..." }],
 *         "role": "model"
 *       },
 *       "finishReason": "STOP",
 *       "index": 0
 *     }
 *   ],
 *   "usageMetadata": { "promptTokenCount": 10, ... }
 * }
 *
 * record を使用することで、Lombok なしで getter・コンストラクタを自動提供する。
 * Jackson 2.12+ により record のデシリアライズが正式サポートされている。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiResponse(
        List<Candidate> candidates,
        UsageMetadata usageMetadata,
        String error
) {

    /**
     * 最初の候補からテキストを取り出すユーティリティメソッド。
     *
     * <p>candidates → [0].content().parts() → [0].text() の順にアクセスする。
     * いずれかが null または空の場合は null を返す。
     *
     * @return AIの返答テキスト。取得できない場合は null
     */
    public String extractText() {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Candidate first = candidates.get(0);
        if (first.content() == null) {
            return null;
        }
        List<Part> parts = first.content().parts();
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        return parts.get(0).text();
    }

    // =========================================================================
    // ネスト record
    // =========================================================================

    /**
     * Gemini API が返す候補（candidate）1件分。
     *
     * @param content      AIが生成したコンテンツ（parts を含む）
     * @param finishReason 生成が終了した理由（例: "STOP"）
     * @param index        候補のインデックス番号
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(
            Content content,
            String finishReason,
            int index
    ) {}

    /**
     * メッセージの内容。parts のリストとロールを保持する。
     *
     * @param parts テキストパーツのリスト
     * @param role  発言者ロール（"model" など）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(
            List<Part> parts,
            String role
    ) {}

    /**
     * テキストの最小単位。
     *
     * @param text 生成されたテキスト
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String text) {}

    /**
     * トークン使用量のメタデータ。
     *
     * @param promptTokenCount     プロンプトのトークン数
     * @param candidatesTokenCount 候補のトークン数
     * @param totalTokenCount      合計トークン数
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsageMetadata(
            int promptTokenCount,
            int candidatesTokenCount,
            int totalTokenCount
    ) {}
}
