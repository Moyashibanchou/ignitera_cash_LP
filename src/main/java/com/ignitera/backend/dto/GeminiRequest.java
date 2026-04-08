package com.ignitera.backend.dto;

import java.util.List;

/**
 * Gemini API へ送信するリクエストの構造体。
 *
 * 対応エンドポイント:
 *   POST https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key={API_KEY}
 *
 * リクエストボディ例:
 * {
 *   "system_instruction": { "parts": [{ "text": "..." }] },
 *   "contents": [
 *     { "role": "user",  "parts": [{ "text": "Hello" }] },
 *     { "role": "model", "parts": [{ "text": "Hi!"   }] }
 *   ],
 *   "generationConfig": { "temperature": 0.85, "maxOutputTokens": 2048 }
 * }
 *
 * 【重要】Jackson は record コンポーネント名をそのまま JSON キーとして使用する。
 * @JsonProperty を使わずコンポーネント名を snake_case にすることで、
 * "system_instruction" キーが確実に送信される。
 */
public record GeminiRequest(

        /**
         * システムプロンプト（人格設定）。
         * コンポーネント名を system_instruction にすることで
         * Jackson が自動的に "system_instruction" キーでシリアライズする。
         */
        SystemInstruction system_instruction,

        /** 会話履歴 + 今回のユーザーメッセージ */
        List<Content> contents,

        /** 生成パラメータ（温度・最大トークン数など） */
        GenerationConfig generationConfig

) {

    // =========================================================================
    // ネスト record 群
    // =========================================================================

    /**
     * system_instruction フィールドに対応するレコード。
     * parts リストにシステムプロンプトのテキストを格納する。
     */
    public record SystemInstruction(List<Part> parts) {}

    /**
     * 会話の1ターン分を表すレコード。
     *
     * @param role  発言者ロール。"user"（ユーザー）または "model"（AI）
     * @param parts 発言内容のリスト（通常は1要素）
     */
    public record Content(String role, List<Part> parts) {}

    /**
     * テキストコンテンツの最小単位。
     *
     * @param text 発言・システムプロンプト等のテキスト本文
     */
    public record Part(String text) {}

    /**
     * Gemini の生成パラメータ。
     *
     * @param temperature     出力の多様性（0.0〜1.0）。高いほど創造的・低いほど一貫した出力
     * @param maxOutputTokens 最大出力トークン数
     * @param topP            Top-P サンプリング（省略可）
     * @param topK            Top-K サンプリング（省略可）
     */
    public record GenerationConfig(
            Double  temperature,
            Integer maxOutputTokens,
            Double  topP,
            Integer topK
    ) {}
}
