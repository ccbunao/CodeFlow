package com.ainative.v2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 扫描范围包含 com.ainative.integration —— TAPD 集成模块按同事提交的 v1 包结构合入
 * (com.ainative.integration.tapd / .provider),保持原包名以便后续与上游对齐。
 */
@SpringBootApplication(scanBasePackages = {"com.ainative.v2", "com.ainative.integration"})
@EnableScheduling
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
