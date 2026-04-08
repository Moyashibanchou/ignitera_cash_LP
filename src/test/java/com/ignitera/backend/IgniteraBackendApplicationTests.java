package com.ignitera.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class IgniteraBackendApplicationTests {

    @Test
    void contextLoads() {
        // Spring アプリケーションコンテキストが正常に起動できることを確認する
    }
}
