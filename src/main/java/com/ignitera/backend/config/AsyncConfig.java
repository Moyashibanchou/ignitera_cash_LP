package com.ignitera.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 非同期処理設定クラス
 *
 * @EnableAsync を付与することで、@Async アノテーションが付いたメソッドを
 * バックグラウンドのスレッドプールで実行できるようにする。
 *
 * 主な用途:
 *   - EmailNotificationService#sendLeadEmail() の非同期実行
 *     メール送信はSMTP通信を伴うため、メインのチャット応答スレッドをブロックしないよう
 *     別スレッドで実行する。
 */
@EnableAsync
@Configuration
public class AsyncConfig {

    /**
     * メール送信専用のスレッドプール Executor Bean。
     *
     * <ul>
     *   <li>コアスレッド数: 2（常時待機するスレッド）</li>
     *   <li>最大スレッド数: 5（同時送信の上限）</li>
     *   <li>キューキャパシティ: 50（処理待ちタスクの最大数）</li>
     *   <li>スレッド名プレフィックス: "ignitera-mail-" でログ追跡を容易にする</li>
     * </ul>
     *
     * Bean 名を "mailTaskExecutor" として定義することで、
     * @Async("mailTaskExecutor") と明示的に指定して使用できる。
     *
     * @return 設定済み ThreadPoolTaskExecutor
     */
    @Bean(name = "mailTaskExecutor")
    public Executor mailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 常時待機するコアスレッド数
        // メール送信は頻繁ではないため少数で十分
        executor.setCorePoolSize(2);

        // 同時実行できる最大スレッド数
        executor.setMaxPoolSize(5);

        // キューに積める最大タスク数（超えた場合は RejectedExecutionException）
        executor.setQueueCapacity(50);

        // ログでスレッドを識別しやすくするプレフィックス
        executor.setThreadNamePrefix("ignitera-mail-");

        // アプリケーション終了時に実行中のタスクが完了するまで待機する
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // シャットダウン時の最大待機時間（秒）
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }
}
