package com.ignitera.backend.dto;

/**
 * AIチャットAPIのレスポンスDTO
 *
 * <p>フロントエンド（Next.js）へ返却するデータ構造。
 * Lombok を使わず Java 17 の record で定義することで、
 * getter・equals・hashCode・toString が自動生成される。
 *
 * <p>フィールド:
 * <ul>
 *   <li>{@code message}      — Gemini APIから返却されたAIの返答テキスト（エラー時は null）</li>
 *   <li>{@code leadDetected} — リード情報が検知された場合 true。メール通知が送信済みであることを示す</li>
 *   <li>{@code errorMessage} — エラー発生時のメッセージ（正常時は null）</li>
 * </ul>
 *
 * <p>使用例:
 * <pre>
 * // 正常レスポンス
 * new ChatResponse("AIの返答テキスト", false, null)
 *
 * // リード検知あり
 * new ChatResponse("AIの返答テキスト", true, null)
 *
 * // エラーレスポンス
 * new ChatResponse(null, false, "エラーメッセージ")
 * </pre>
 */
public record ChatResponse(
        String message,
        boolean leadDetected,
        String errorMessage
) {}
