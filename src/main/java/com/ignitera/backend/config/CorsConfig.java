package com.ignitera.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS（Cross-Origin Resource Sharing）設定クラス
 *
 * setAllowedOriginPatterns("*") を使用することで、
 * ワイルドカード指定と allowCredentials=true の両立が可能になる。
 * （setAllowedOrigins("*") + allowCredentials=true はブラウザ仕様で禁止）
 *
 * 本番環境では setAllowedOriginPatterns を特定ドメインに絞ること。
 */
@Configuration
public class CorsConfig {

    /**
     * CorsFilter Bean を登録する。
     * Spring Security を使用していない場合でも確実にCORSヘッダーを付与するため、
     * WebMvcConfigurer ではなく CorsFilter を使用している。
     *
     * @return 設定済みの CorsFilter
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // -----------------------------------------------------------------------
        // 許可するオリジン
        // setAllowedOriginPatterns("*") はワイルドカードと allowCredentials の
        // 両立をサポートする Spring 5.3+ の推奨メソッド。
        // -----------------------------------------------------------------------
        config.setAllowedOriginPatterns(List.of("*"));

        // -----------------------------------------------------------------------
        // 許可するHTTPメソッド
        // -----------------------------------------------------------------------
        config.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"   // プリフライトリクエスト対応
        ));

        // -----------------------------------------------------------------------
        // 許可するリクエストヘッダー（* で全許可）
        // -----------------------------------------------------------------------
        config.setAllowedHeaders(List.of("*"));

        // -----------------------------------------------------------------------
        // レスポンスヘッダーのうちフロントエンドから読み取り可能にするもの
        // -----------------------------------------------------------------------
        config.setExposedHeaders(List.of(
                "Authorization",
                "Content-Disposition"
        ));

        // -----------------------------------------------------------------------
        // 認証情報の送信を許可（setAllowedOriginPatterns と併用可能）
        // -----------------------------------------------------------------------
        config.setAllowCredentials(true);

        // -----------------------------------------------------------------------
        // プリフライトリクエストのキャッシュ時間（秒）
        // -----------------------------------------------------------------------
        config.setMaxAge(3600L);

        // すべてのエンドポイント（/**）に上記のCORS設定を適用する
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
