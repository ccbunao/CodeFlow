package com.ainative.integration.tapd;

import com.ainative.integration.common.ExternalProjectBindingDO;
import com.ainative.integration.common.ExternalProjectBindingDao;
import com.ainative.integration.common.IntegrationOutboxDO;
import com.ainative.integration.provider.ExternalPlatformConfig;
import com.ainative.integration.receipt.ExternalOperationReadbackHandler;
import com.ainative.json.JSON;
import com.ainative.json.JSONObject;
import com.ainative.security.crypto.SecretCrypto;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TapdCommentReadback implements ExternalOperationReadbackHandler {
    private static final Pattern MARKER = Pattern.compile("<!-- (an-op:[a-f0-9]{24}) -->");
    private final ExternalProjectBindingDao bindings;
    private final SecretCrypto crypto;
    private final TapdWorkitemProvider provider;

    public TapdCommentReadback(ExternalProjectBindingDao bindings, SecretCrypto crypto, TapdWorkitemProvider provider) {
        this.bindings = bindings;
        this.crypto = crypto;
        this.provider = provider;
    }

    @Override
    public String connector() {
        return TapdWorkitemProvider.PROVIDER;
    }

    @Override
    public boolean supports(String eventType) {
        return "COMMENT_CREATE".equals(eventType);
    }

    @Override
    public ReadbackResult readback(IntegrationOutboxDO receipt) {
        ExternalProjectBindingDO binding = bindings.findById(receipt.getBindingId());
        if (binding == null || !Objects.equals(receipt.getTenantId(), binding.getTenantId())
                || !connector().equals(binding.getProvider()) || !Integer.valueOf(1).equals(binding.getEnabled())) {
            return ReadbackResult.unavailable("TAPD binding unavailable");
        }
        JSONObject payload = JSON.parseObject(receipt.getPayloadJson());
        String marker = payload.getString("marker");
        Matcher match = MARKER.matcher(marker == null ? "" : marker);
        if (!match.matches()) return ReadbackResult.unavailable("TAPD comment marker unavailable");
        var config = new ExternalPlatformConfig(binding.getBaseUrl(), binding.getClientKey(),
                crypto.decrypt(binding.getCredentialRef()), binding.getRegionId(), binding.getExternalProjectId());
        if (provider.hasCommentMarker(config, payload.getString("externalWorkitemId"), match.group(1))) {
            return ReadbackResult.found();
        }
        // A successful comment can be temporarily invisible. Never blindly POST again.
        return ReadbackResult.unavailable("TAPD comment outcome is not yet confirmed");
    }
}
