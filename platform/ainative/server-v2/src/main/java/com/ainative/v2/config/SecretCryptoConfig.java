package com.ainative.v2.config;

import com.ainative.security.crypto.AesGcmSecretCrypto;
import com.ainative.security.crypto.SecretCrypto;
import com.ainative.security.crypto.SecretCryptoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 外部平台凭据加密(AES-GCM)。TAPD 的 API 密钥以密文存 credential_ref,
 * 主密钥由 ainative.security.secret-crypto.master-key 提供
 * (环境变量 AINATIVE_SECRET_MASTER_KEY,Base64 32 字节,openssl rand -base64 32)。
 *
 * <p>未配置主密钥时不阻断启动:平台照常跑,只有真正存取外部平台凭据时才报错。
 */
@Configuration
@EnableConfigurationProperties(SecretCryptoProperties.class)
public class SecretCryptoConfig {
    @Bean
    public SecretCrypto secretCrypto(SecretCryptoProperties properties) {
        String masterKey = properties == null ? null : properties.getMasterKey();
        if (masterKey == null || masterKey.isBlank()) {
            return new UnavailableSecretCrypto();
        }
        return new AesGcmSecretCrypto(properties);
    }
}
