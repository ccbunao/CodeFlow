package com.ainative.v2.config;

import com.ainative.security.crypto.SecretCrypto;

/**
 * 未配置凭据加密主密钥时的占位实现。
 *
 * <p>外部平台(TAPD 等)的 API 密钥要以密文落库,没有主密钥就没法加解密。
 * 但这不该让整个平台起不来 —— 平台照常启动,只有真正要存取外部平台凭据时
 * 才报错,并把"该配哪个环境变量"直接告诉使用者。
 */
public class UnavailableSecretCrypto implements SecretCrypto {
    private static final String HINT = "未配置凭据加密主密钥(环境变量 AINATIVE_SECRET_MASTER_KEY,"
            + "值为 openssl rand -base64 32 的输出),无法存取外部平台凭据";

    @Override
    public String encrypt(String plaintext) {
        throw new com.ainative.v2.common.BizException("10001", HINT);
    }

    @Override
    public String decrypt(String ciphertext) {
        throw new com.ainative.v2.common.BizException("10001", HINT);
    }

    @Override
    public String mask(String value) {
        return "****";
    }
}
