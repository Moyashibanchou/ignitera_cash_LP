package com.ignitera.backend.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient の Bean 設定クラス。
 *
 * Gemini API との HTTP 通信に使用する WebClient を定義する。
 * タイムアウト・ログ・共通ヘッダーを一元管理することで、
 * 各 Service クラスを薄く保つ。
 */
@Configuration
public class WebClientConfig {

    /** 接続タイムアウト（ミリ秒）: 5秒 */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** 読み取りタイムアウト（秒）: 30秒（Gemini の推論に時間がかかる場合を考慮） */
    private static final int READ_TIMEOUT_SEC = 30;

    /** 書き込みタイムアウト（秒）: 10秒 */
    private static final int WRITE_TIMEOUT_SEC = 10;

    /** レスポンスタイムアウト（秒）: 35秒（read timeout より少し長く設定） */
    private static final int RESPONSE_TIMEOUT_SEC = 35;

    /**
     * Gemini API 通信用の WebClient Bean。
     *
     * <ul>
     *   <li>Reactor Netty をベースとした HTTP クライアントを使用</li>
     *   <li>接続・読み取り・書き込みの各タイムアウトを設定</li>
     *   <li>共通リクエストヘッダー（Content-Type: application/json）を設定</li>
     *   <li>リクエスト・レスポンスのデバッグログフィルターを追加</li>
     * </ul>
     *
     * @return 設定済み WebClient インスタンス
     */
    @Bean
    public WebClient geminiWebClient() {
        HttpClient httpClient = HttpClient.create()
                // 接続タイムアウト
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                // レスポンス全体のタイムアウト
                .responseTimeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SEC))
                // 読み取り・書き込みタイムアウト（ハンドラーベース）
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(READ_TIMEOUT_SEC, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS))
                );

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                // リクエストログフィルター（DEBUGレベル）
                .filter(logRequest())
                // レスポンスログフィルター（DEBUGレベル）
                .filter(logResponse())
                .build();
    }

    /**
     * リクエスト内容をログ出力する ExchangeFilterFunction。
     * メソッド・URL を DEBUG レベルで記録する。
     */
    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            System.out.printf("[WebClient] >>> %s %s%n",
                    clientRequest.method(),
                    clientRequest.url());
            return Mono.just(clientRequest);
        });
    }

    /**
     * レスポンスのステータスコードをログ出力する ExchangeFilterFunction。
     * エラーステータス（4xx / 5xx）も記録する。
     */
    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            System.out.printf("[WebClient] <<< HTTP %s%n",
                    clientResponse.statusCode());
            return Mono.just(clientResponse);
        });
    }
}
