package com.ignitera.backend.dto;

/**
 * リード情報を保持するDTO
 *
 * AIチャットの会話から抽出された見込み顧客情報を格納する。
 * record 型により、コンストラクタ・accessor（companyName() など）・
 * equals / hashCode / toString が自動生成される。
 *
 * @param companyName         企業名（例: 株式会社〇〇）
 * @param industry            業種（例: 飲食、IT、不動産）
 * @param email               メールアドレス
 * @param conversationSummary 会話の要約（直近のやり取りを整形した文字列）
 */
public record LeadInfo(
        String companyName,
        String industry,
        String email,
        String conversationSummary
) {

    /**
     * リード情報が1件以上含まれているかを判定する。
     * メールアドレス・企業名・業種のいずれか1つ以上が検知されていれば {@code true} を返す。
     *
     * @return リード情報が存在する場合 {@code true}
     */
    public boolean hasAnyLeadInfo() {
        return isNotBlank(email)
                || isNotBlank(companyName)
                || isNotBlank(industry);
    }

    /**
     * メールアドレスが検知されているかを返す。
     *
     * @return メールアドレスが存在する場合 {@code true}
     */
    public boolean hasEmail() {
        return isNotBlank(email);
    }

    /**
     * 企業名が検知されているかを返す。
     *
     * @return 企業名が存在する場合 {@code true}
     */
    public boolean hasCompanyName() {
        return isNotBlank(companyName);
    }

    /**
     * 業種が検知されているかを返す。
     *
     * @return 業種が存在する場合 {@code true}
     */
    public boolean hasIndustry() {
        return isNotBlank(industry);
    }

    // -------------------------------------------------------------------------
    // private helper
    // -------------------------------------------------------------------------

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
